(function () {
  const statusEl = document.getElementById("status");
  const frameEl = document.getElementById("frame");
  const overlay = document.getElementById("overlay");

  let width = 1280;
  let height = 720;
  let socket = null;
  let objectUrl = null;
  let waitingAck = false;
  const pointers = new Map();

  function pinFromUrl() {
    const m = location.search.match(/[?&]pin=([^&]+)/);
    return m ? decodeURIComponent(m[1]) : "";
  }

  function setStatus(text) {
    statusEl.textContent = text;
  }

  function send(obj) {
    if (socket && socket.readyState === 1) {
      socket.send(JSON.stringify(obj));
    }
  }

  function connect() {
    const pin = pinFromUrl();
    if (!/^\d{4}$/.test(pin)) {
      setStatus("add ?pin=NNNN to the URL");
      return;
    }
    const proto = location.protocol === "https:" ? "wss" : "ws";
    const url = proto + "://" + location.host + "/mirror?pin=" + encodeURIComponent(pin);
    setStatus("connecting");
    socket = new WebSocket(url);
    socket.binaryType = "arraybuffer";
    socket.onopen = function () {
      setStatus("linked");
      send({ op: "heartbeat" });
    };
    socket.onclose = function () {
      setStatus("disconnected — retrying");
      waitingAck = false;
      setTimeout(connect, 1200);
    };
    socket.onerror = function () {
      setStatus("socket error");
    };
    socket.onmessage = function (ev) {
      if (typeof ev.data === "string") {
        try {
          const msg = JSON.parse(ev.data);
          if (msg.op === "state") {
            width = msg.w || width;
            height = msg.h || height;
            setStatus((msg.live ? "live" : "idle") + " · " + (msg.phase || ""));
          } else if (msg.op === "occupied") {
            setStatus(msg.reason || "busy");
          }
        } catch (_) {}
        return;
      }
      const blob = new Blob([ev.data], { type: "image/jpeg" });
      if (objectUrl) URL.revokeObjectURL(objectUrl);
      objectUrl = URL.createObjectURL(blob);
      waitingAck = true;
      frameEl.onload = function () {
        if (waitingAck) {
          waitingAck = false;
          send({ op: "frame-ack" });
        }
      };
      frameEl.src = objectUrl;
    };
  }

  setInterval(function () {
    send({ op: "heartbeat" });
  }, 500);

  function mapPoint(clientX, clientY) {
    const rect = frameEl.getBoundingClientRect();
    if (rect.width <= 0 || rect.height <= 0 || width <= 0 || height <= 0) return null;
    const scale = Math.min(rect.width / width, rect.height / height);
    const drawW = width * scale;
    const drawH = height * scale;
    const ox = rect.left + (rect.width - drawW) / 2;
    const oy = rect.top + (rect.height - drawH) / 2;
    const nx = (clientX - ox) / drawW;
    const ny = (clientY - oy) / drawH;
    if (nx < 0 || nx > 1 || ny < 0 || ny > 1) return null;
    return { nx: nx, ny: ny };
  }

  function emitPointers() {
    const points = [];
    pointers.forEach(function (p, id) {
      points.push({
        slot: Math.min(1, id),
        nx: p.nx,
        ny: p.ny,
        pressed: p.pressed,
      });
    });
    send({ op: "pointer", points: points });
  }

  function pointerId(e) {
    return e.pointerId || 0;
  }

  overlay.addEventListener("pointerdown", function (e) {
    const mapped = mapPoint(e.clientX, e.clientY);
    if (!mapped) return;
    overlay.setPointerCapture(e.pointerId);
    pointers.set(pointerId(e), { nx: mapped.nx, ny: mapped.ny, pressed: true });
    emitPointers();
    e.preventDefault();
  });

  overlay.addEventListener("pointermove", function (e) {
    if (!pointers.has(pointerId(e))) return;
    const mapped = mapPoint(e.clientX, e.clientY);
    if (!mapped) return;
    pointers.set(pointerId(e), { nx: mapped.nx, ny: mapped.ny, pressed: true });
    emitPointers();
    e.preventDefault();
  });

  function endPointer(e) {
    if (!pointers.has(pointerId(e))) return;
    const cur = pointers.get(pointerId(e));
    pointers.set(pointerId(e), { nx: cur.nx, ny: cur.ny, pressed: false });
    emitPointers();
    pointers.delete(pointerId(e));
    emitPointers();
    e.preventDefault();
  }

  overlay.addEventListener("pointerup", endPointer);
  overlay.addEventListener("pointercancel", endPointer);

  window.addEventListener("blur", function () {
    pointers.clear();
    send({ op: "pointer", points: [] });
  });

  connect();
})();
