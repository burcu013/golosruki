package ru.alexandr.golosruki

import android.content.Context
import android.os.Build
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AdbStream
import org.conscrypt.Conscrypt
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Security
import java.security.cert.Certificate
import java.util.Date
import java.util.Random
import sun.security.x509.AlgorithmId
import sun.security.x509.CertificateAlgorithmId
import sun.security.x509.CertificateExtensions
import sun.security.x509.CertificateIssuerName
import sun.security.x509.CertificateSerialNumber
import sun.security.x509.CertificateSubjectName
import sun.security.x509.CertificateValidity
import sun.security.x509.CertificateVersion
import sun.security.x509.CertificateX509Key
import sun.security.x509.KeyIdentifier
import sun.security.x509.PrivateKeyUsageExtension
import sun.security.x509.SubjectKeyIdentifierExtension
import sun.security.x509.X500Name
import sun.security.x509.X509CertImpl
import sun.security.x509.X509CertInfo

/**
 * Вариант B: встроенный adb-клиент.
 * Сопрягается с беспроводной отладкой телефона по коду и сам выполняет
 * `pm grant ... WRITE_SECURE_SETTINGS` — без Termux и компьютера.
 */
class AdbManager private constructor() : AbsAdbConnectionManager() {

    private val privateKeyField: PrivateKey
    private val certificateField: Certificate

    init {
        setApi(Build.VERSION.SDK_INT)
        val keySize = 2048
        val gen = KeyPairGenerator.getInstance("RSA")
        gen.initialize(keySize, SecureRandom.getInstance("SHA1PRNG"))
        val pair: KeyPair = gen.generateKeyPair()
        val publicKey = pair.public
        privateKeyField = pair.private

        val subject = "CN=GolosRuki"
        val algorithmName = "SHA512withRSA"
        val expiry = System.currentTimeMillis() + 86400000L * 365
        val ext = CertificateExtensions()
        ext.set("SubjectKeyIdentifier",
            SubjectKeyIdentifierExtension(KeyIdentifier(publicKey).identifier))
        val x500 = X500Name(subject)
        val notBefore = Date()
        val notAfter = Date(expiry)
        ext.set("PrivateKeyUsage", PrivateKeyUsageExtension(notBefore, notAfter))
        val validity = CertificateValidity(notBefore, notAfter)
        val info = X509CertInfo()
        info.set("version", CertificateVersion(2))
        info.set("serialNumber", CertificateSerialNumber(Random().nextInt() and Int.MAX_VALUE))
        info.set("algorithmID", CertificateAlgorithmId(AlgorithmId.get(algorithmName)))
        info.set("subject", CertificateSubjectName(x500))
        info.set("key", CertificateX509Key(publicKey))
        info.set("validity", validity)
        info.set("issuer", CertificateIssuerName(x500))
        info.set("extensions", ext)
        val cert = X509CertImpl(info)
        cert.sign(privateKeyField, algorithmName)
        certificateField = cert
    }

    override fun getPrivateKey(): PrivateKey = privateKeyField
    override fun getCertificate(): Certificate = certificateField
    override fun getDeviceName(): String = "GolosRuki"

    companion object {
        @Volatile private var INSTANCE: AdbManager? = null
        fun getInstance(): AdbManager =
            INSTANCE ?: synchronized(this) { INSTANCE ?: AdbManager().also { INSTANCE = it } }

        private var conscryptInstalled = false
        private fun ensureConscrypt() {
            if (conscryptInstalled) return
            runCatching { Security.insertProviderAt(Conscrypt.newProvider(), 1) }
            conscryptInstalled = true
        }

        /**
         * Сопрячься по коду, подключиться и выдать разрешение.
         * Возвращает текст результата (для показа пользователю). Блокирующий — вызывать в фоне.
         */
        fun pairAndGrant(context: Context, host: String, pairPort: Int, code: String): String {
            ensureConscrypt()
            val m = getInstance()
            val paired = m.pair(host, pairPort, code)
            if (!paired) return "Сопряжение не удалось. Проверьте адрес, порт и код."
            val connected = m.autoConnect(context, 15_000)
            if (!connected) return "Сопряжение прошло, но подключиться не удалось. Беспроводная отладка должна оставаться включённой."
            val cmd = "pm grant ru.alexandr.golosruki android.permission.WRITE_SECURE_SETTINGS; echo DONE"
            val stream: AdbStream = m.openStream("shell:$cmd")
            val out = stream.openInputStream().bufferedReader().use { it.readText() }
            runCatching { stream.close() }
            runCatching { m.close() }
            return if (out.contains("DONE")) "Готово ✅ Разрешение выдано. Можно пользоваться диктовкой везде."
                else "Команда выполнена, проверьте статус. Ответ: ${out.trim().take(200)}"
        }
    }
}
