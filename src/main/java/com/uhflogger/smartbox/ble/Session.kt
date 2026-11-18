package com.uhflogger.smartbox.ble

import com.uhflogger.smartbox.ble.SmartboxCrypto.Direction
import com.uhflogger.smartbox.ble.SmartboxProtocol as P
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class HandshakeException(message: String, val kind: Kind) : Exception(message) {
    enum class Kind { WRONG_MODULE, PROTOCOL_VERSION, FAKE_DEVICE, CLOCK }
}

/** O que o handshake precisa de fora. Injetado para o teste ser determinístico. */
class HandshakeDeps(
    /** Chave de campo K (ou a de bancada, só em teste). Nunca logar. */
    val key: ByteArray,
    /** CSPRNG (SecureRandom no app). */
    val randomBytes: (Int) -> ByteArray,
    val now: () -> Long,
)

/**
 * Sessão autenticada e cifrada com uma smartbox (seções 4.5 e 4.6 do guia).
 *
 * Handshake em claro (HI → CHALLENGE → AUTH → AUTH_OK) e, depois, toda mensagem
 * cifrada com AES-128-GCM e contadores implícitos por direção.
 *
 * Erro de status (1–5) não quebra a sessão. Erro de cripto, de op trocado ou
 * timeout quebra o link: a sessão morre e quem a abriu desconecta.
 */
class SmartboxSession(private val link: SmartboxLink) : DownloadSession {
    private var sessionKey: ByteArray? = null
    private var txCounter = 0L
    private var rxCounter = 0L
    private var challengeInfo: Challenge? = null
    /** Uma pergunta por vez: pergunta nova no meio da resposta faz o ESP desconectar. */
    private val mutex = Mutex()

    val challenge: Challenge get() = challengeInfo ?: error("Sessão sem handshake")

    val isOpen: Boolean get() = sessionKey != null && !link.isBroken

    /**
     * Handshake completo. [expectedModuleId] é o MAC do anúncio: CHALLENGE com
     * outro MAC = desconectar.
     */
    suspend fun handshake(expectedModuleId: String, deps: HandshakeDeps): Challenge {
        if (deps.now() < P.MIN_VALID_TIME_MS) {
            // O AUTH acerta o relógio da smartbox: data errada no celular estraga
            // a hora da caixa e das leituras.
            throw HandshakeException(
                "A data do aparelho está errada. Ligue a data e hora automáticas.",
                HandshakeException.Kind.CLOCK,
            )
        }

        val challenge = parseChallenge(link.exchange(byteArrayOf(Op.HI.toByte()), Timeouts.HANDSHAKE_MS))

        if (challenge.moduleId != expectedModuleId) {
            link.breakLink("MAC do CHALLENGE diferente do anúncio")
            throw HandshakeException("Smartbox diferente da anunciada", HandshakeException.Kind.WRONG_MODULE)
        }
        if (challenge.proto != P.VERSION) {
            link.breakLink("Protocolo incompatível")
            throw HandshakeException(
                "Smartbox no protocolo ${challenge.proto}; este app fala o ${P.VERSION}. Atualize o app.",
                HandshakeException.Kind.PROTOCOL_VERSION,
            )
        }

        val nonceCel = deps.randomBytes(P.NONCE_LENGTH)
        check(nonceCel.size == P.NONCE_LENGTH) { "CSPRNG devolveu tamanho errado" }
        val auth = SmartboxCrypto.buildAuth(deps.key, challenge.nonceEsp, nonceCel, deps.now())

        // HMAC errado: o ESP desconecta SEM responder. Vira timeout/queda aqui.
        val authOk = link.exchange(auth, Timeouts.HANDSHAKE_MS)

        if (!SmartboxCrypto.verifyAuthOk(deps.key, authOk, challenge.nonceEsp, nonceCel)) {
            link.breakLink("AUTH_OK inválido")
            throw HandshakeException("A smartbox não provou a chave (aparelho falso?)", HandshakeException.Kind.FAKE_DEVICE)
        }

        sessionKey = SmartboxCrypto.deriveSessionKey(deps.key, challenge.nonceEsp, nonceCel)
        txCounter = 0
        rxCounter = 0
        challengeInfo = challenge
        return challenge
    }

    /**
     * Pergunta cifrada. Devolve a resposta com qualquer status; erros de
     * transporte/cripto quebram o link e lançam.
     */
    override suspend fun call(op: Int, data: ByteArray, timeoutMs: Long): Response = mutex.withLock {
        val key = sessionKey ?: error("Sessão sem handshake")
        val plain = Bytes.concat(byteArrayOf(op.toByte()), data)
        if (plain.size > P.MAX_PLAIN_MESSAGE) throw ProtocolException("Pergunta grande demais")

        val sealed = SmartboxCrypto.seal(key, Direction.APP_TO_ESP, txCounter++, plain)
        val reply = link.exchange(sealed, timeoutMs)

        val opened = try {
            SmartboxCrypto.open(key, Direction.ESP_TO_APP, rxCounter++, reply)
        } catch (e: Exception) {
            link.breakLink("Resposta com tag inválido", e)
            throw ProtocolException("Resposta da smartbox não confere")
        }
        try {
            parseResponse(op, opened)
        } catch (e: ProtocolException) {
            link.breakLink("Resposta inválida", e)
            throw e
        }
    }

    /** Como [call], mas status ≠ 0 vira [SmartboxStatusException] (a sessão segue válida). */
    suspend fun callOk(op: Int, data: ByteArray = ByteArray(0), timeoutMs: Long = Timeouts.RESPONSE_MS): ByteArray {
        val res = call(op, data, timeoutMs)
        if (res.status != Status.OK) throw SmartboxStatusException(op, res.status, res.data)
        return res.data
    }
}
