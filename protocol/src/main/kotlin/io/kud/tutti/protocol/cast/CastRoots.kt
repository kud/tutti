package io.kud.tutti.protocol.cast

import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64

/**
 * Google's public Cast root certificates, the only trust anchors of the Cast plane.
 *
 * Source: chromium/openscreen, cast/common/certificate/cast_root_ca_cert_der-inc.h and
 * eureka_root_ca_der-inc.h (commit 2a78d154c5786ef4dfdb04ad1ce720596f3cb47e, read 2026-10-25),
 * the same anchors Chromium and Open Screen trust for Cast device authentication. Public
 * certificates, not secrets. SHA-256 fingerprints, to check against any future copy:
 * - CN=Cast Root CA, OU=Cast, until 2034-03-28:
 *   80:9A:F1:47:00:B3:FE:26:11:AD:59:7E:B1:58:4D:63:54:31:3B:64:CC:DB:33:90:F0:97:FA:3E:58:26:D6:CA
 * - CN=Eureka Root CA, OU=Google TV, until 2032-12-12:
 *   CA:F6:D1:E3:7B:53:22:03:E0:1A:76:BF:07:18:7B:B7:31:CC:D3:88:01:56:5A:B2:21:1A:2C:0A:B7:F3:BC:46
 */
object CastRoots {
    val all: List<X509Certificate> by lazy { listOf(CAST_ROOT_CA, EUREKA_ROOT_CA).map(::decode) }

    private fun decode(base64: String): X509Certificate =
        CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(Base64.getDecoder().decode(base64))) as X509Certificate

    private const val CAST_ROOT_CA =
        "MIIDxTCCAq2gAwIBAgIBAjANBgkqhkiG9w0BAQUFADB1MQswCQYDVQQGEwJVUzETMBEGA1UECAwKQ2FsaWZvcm5pYTEWMBQGA1UEBwwNTW91bnRhaW4gVmlldzETMBEGA1UECgwKR29vZ2xlIEluYzENMAsGA1UECwwEQ2FzdDEVMBMGA1UEAwwMQ2FzdCBSb290IENBMB4XDTE0MDQwMjE3MzQyNloXDTM0MDMyODE3MzQyNlowdTELMAkGA1UEBhMCVVMxEzARBgNVBAgMCkNhbGlmb3JuaWExFjAUBgNVBAcMDU1vdW50YWluIFZpZXcxEzARBgNVBAoMCkdvb2dsZSBJbmMxDTALBgNVBAsMBENhc3QxFTATBgNVBAMMDENhc3QgUm9vdCBDQTCCASIwDQYJKoZIhvcNAQEBBQADggEPADCCAQoCggEBALrZZZ3aOdPBd/bU0K6PWAhoOUqV7XDP/XkIqarl6binLaBnR4qeyc9wswWHaRHscJiXw+bDw+u9xrA9/E/BXjif2s9zMAZbeTfBXoyHR5SaQZIq1pXEcVwnXQixgMaSvRvjQZeh7HWfVZ4+n48cx2VkB9OzlqEEn5HE3gp7bNnIwHgxoBlCqeiD48788c7CLiRGlQkZysBGsuUButdP87/2aa2ZBPqgBzkO5t9RRwfA5KlcS5TFL7OgMH/nlWuyrzIN8YzVbct7R6cIq8sno03PSlrxBdH4YsUQKnRpquZLlvub2GPkWGbTrYpu/3te+aVWHi2CMVvw4iTmQUofrhMCAwEAAaNgMF4wDwYDVR0TBAgwBgEB/wIBAjAdBgNVHQ4EFgQUfJoefd95VLzXzF7KmYZFeWV0KBkwHwYDVR0jBBgwFoAUfJoefd95VLzXzF7KmYZFeWV0KBkwCwYDVR0PBAQDAgEGMA0GCSqGSIb3DQEBBQUAA4IBAQCA9Fr7PSgZUSDX1PsSl0pl8lg1kncwavHXtlEaf5rNx3sDQq1VagCv8OEGwr1reHXb/kERU0o5u5o6xlk0Lywz47LWXH/deOtxWznag5DFMeI/I+/a6ystd17ew0PSyWtZgsrV7fqhZFvL8Q0aYuGc6KcYcPBfF5b47Ybbrh3gzz5dLu4WbZUrPP2X8wVaJGhNObb45Fi69eAmeFHFW11OCeVsR4t6Wi6JU+bMNlsmPPhyQwKC0ivN8NOj7BM+UtWDPQfcHUNlejMCAaPOt9ZgUTsJwiOKMv6YGWBik4XNNEbb1SMPedp3ACoCbYNYzgN3NeGjIJPCSqKkRhx1LB9N"

    private const val EUREKA_ROOT_CA =
        "MIIDwzCCAqugAwIBAgIBATANBgkqhkiG9w0BAQUFADB8MQswCQYDVQQGEwJVUzETMBEGA1UECAwKQ2FsaWZvcm5pYTEWMBQGA1UEBwwNTW91bnRhaW4gVmlldzETMBEGA1UECgwKR29vZ2xlIEluYzESMBAGA1UECwwJR29vZ2xlIFRWMRcwFQYDVQQDDA5FdXJla2EgUm9vdCBDQTAeFw0xMjEyMTcyMjM5MzNaFw0zMjEyMTIyMjM5MzNaMHwxCzAJBgNVBAYTAlVTMRMwEQYDVQQIDApDYWxpZm9ybmlhMRYwFAYDVQQHDA1Nb3VudGFpbiBWaWV3MRMwEQYDVQQKDApHb29nbGUgSW5jMRIwEAYDVQQLDAlHb29nbGUgVFYxFzAVBgNVBAMMDkV1cmVrYSBSb290IENBMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAuRHQ6hLcMuHfXDNrGXMdnZ7QOXa/pYQJpv1ubencjzZO6YgCvZ/06ET9TPWaAlZqRypjbFhFzHxmJNx5ecMqpLKLoPeitc0Gftu+7AyG8g0kYHSEyikjhALYp+078ewmR1TjsS3mZA/2csXpmFIXwPzyLCDIQPhHyTKeO5exi/WYJHBjZhnBUugEBT1fjbzYS693mG8feNG2UCdN5OwUaWcfWK+poBEmPJQyB3/X6Wkfrj9PY4qPidbyGXhcIY6xtlfYwOHufW7d8ToKavG6//mDL9y1pCAXYzbvyGIZzFbOsuoxiUt4WMG/AxOZ4BLyiKqblNrddnkXHjTRCsQHRQIDAQABo1AwTjAdBgNVHQ4EFgQURE4qR1jYuUiR9k/OdKkdMpqNjekwHwYDVR0jBBgwFoAURE4qR1jYuUiR9k/OdKkdMpqNjekwDAYDVR0TBAUwAwEB/zANBgkqhkiG9w0BAQUFAAOCAQEAP8gmoG5cBUB5oZipM95odIXurrccM1mwEd6f9E/T61EJfUd+blGF9FTNg5glsbqwV+yT2xLi7FFJepZzm8iWbYWM0+E8+jLiWAx3bYcMNAGqMKl24MDn214b6RAwpOAJSSa5WM1aB+VQdd6aO/ZTfrFTXkUnTxfjCDOyUAq79PwllyneQXUw+nc4qmWKc0/qEXvrfBdgJw68PnZS2IvtGvjrN7sR/a5wFwr+4K0Gsx9pinIEwsAzC9YvY0wzERS4YjaIxQNlARmj7wC7bw6S/zQcodYx0Fxen5l9x8q9fHIL9Fylfm4EqNKZLFEBFP6iSPB+voQNtNPi8w593ov1Mw=="
}
