package ru.alexandr.golosruki

import android.content.Context
import android.os.Build
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AdbStream
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.conscrypt.Conscrypt
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Security
import java.security.cert.Certificate
import java.util.Date

/**
 * Вариант B: встроенный adb-клиент.
 * Сопрягается с беспроводной отладкой телефона по коду и сам выполняет
 * `pm grant ... WRITE_SECURE_SETTINGS` — без Termux и компьютера.
 * Сертификат генерируется через BouncyCastle.
 */
class AdbManager private constructor() : AbsAdbConnectionManager() {

    private val privateKeyField: PrivateKey
    private val certificateField: Certificate

    init {
        setApi(Build.VERSION.SDK_INT)
        val gen = KeyPairGenerator.getInstance("RSA")
        gen.initialize(2048, SecureRandom.getInstance("SHA1PRNG"))
        val pair: KeyPair = gen.generateKeyPair()
        val publicKey = pair.public
        privateKeyField = pair.private

        val subject = X500Name("CN=GolosRuki")
        val notBefore = Date()
        val notAfter = Date(System.currentTimeMillis() + 86400000L * 365)
        val serial = BigInteger.valueOf(System.currentTimeMillis())
        val builder = JcaX509v3CertificateBuilder(subject, serial, notBefore, notAfter, subject, publicKey)
        builder.addExtension(
            Extension.subjectKeyIdentifier, false,
            JcaX509ExtensionUtils().createSubjectKeyIdentifier(publicKey)
        )
        val signer = JcaContentSignerBuilder("SHA512withRSA").build(privateKeyField)
        certificateField = JcaX509CertificateConverter().getCertificate(builder.build(signer))
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
         * Возвращает текст результата. Блокирующий — вызывать в фоне.
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
