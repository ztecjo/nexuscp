package com.ztec.cplay.mfi

import android.util.Base64

/**
 * Offline MFi identity embedded in source (PKCS#8 private key + PKCS#7 certificate).
 * Not loaded from APK assets.
 */
internal object OfflineMfiIdentity {
    val privateKeyPk8: ByteArray
        get() = decode(PRIVATE_KEY_PK8_B64)

    val certificateP7b: ByteArray
        get() = decode(CERTIFICATE_P7B_B64)

    private fun decode(value: String): ByteArray =
        Base64.decode(value, Base64.DEFAULT)

    private val PRIVATE_KEY_PK8_B64 =
            "MEECAQAwEwYHKoZIzj0CAQYIKoZIzj0DAQcEJzAlAgEBBCAv4C+7aHy52zIUZMW2bAJVb+f2A+E8" +
            "8wS9ep/vXJi6hg=="

    private val CERTIFICATE_P7B_B64 =
            "MIICWwYJKoZIhvcNAQcCoIICTDCCAkgCAQExADALBgkqhkiG9w0BBwGgggIuMIICKjCCAdGgAwIB" +
            "AgIQJKy7SGRvgHf3RjUjCFtg0jAKBggqhkjOPQQDAjCBiTELMAkGA1UEBhMCVVMxEzARBgNVBAoT" +
            "CkFwcGxlIEluYy4xJjAkBgNVBAsTHUFwcGxlIENlcnRpZmljYXRpb24gQXV0aG9yaXR5MT0wOwYD" +
            "VQQDEzRBcHBsZSBBY2Nlc3NvcmllcyBDZXJ0aWZpY2F0aW9uIEF1dGhvcml0eSAtIDAwMDAwMDAy" +
            "MB4XDTE4MDYxMzA4MzI1OVoXDTQ5MTIzMTIzNTk1OVowbTELMAkGA1UEBhMCVVMxEzARBgNVBAoT" +
            "CkFwcGxlIEluYy4xGjAYBgNVBAsTEUFwcGxlIEFjY2Vzc29yaWVzMS0wKwYDVQQDFCRJUEFfMjRB" +
            "Q0JCNDg2NDZGODA3N0Y3NDYzNTIzMDg1QjYwRDIwWTATBgcqhkjOPQIBBggqhkjOPQMBBwNCAAT0" +
            "mPfItJFateYmSe89Z9Bpf4HwogWewlz3ZfUgQkbuxvuvw9vGmuyMlitYkr7QNJM9/J4shFRgN1Ge" +
            "sptbVnEpozYwNDAyBgkqhkiG92NkBiQBAf8EIgQgAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "AAAAAAIwCgYIKoZIzj0EAwIDRwAwRAIgaYL03y8Xje/EKdEqhBoRJLB071JCP69YmNOQXVv+qfkC" +
            "IFngsPKacTAAVavi1E47l28ltIQslTc7ABuQ5c+lt47AoQAxAA=="
}