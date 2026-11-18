package com.uhflogger.smartbox

import com.uhflogger.smartbox.ble.Advertisement
import com.uhflogger.smartbox.ble.GattException
import com.uhflogger.smartbox.ble.LinkBrokenException
import com.uhflogger.smartbox.ble.SmartboxTelemetry
import com.uhflogger.smartbox.domain.FarmLabel
import com.uhflogger.smartbox.domain.FarmRules
import com.uhflogger.smartbox.radio.RadioDecision
import com.uhflogger.smartbox.radio.RadioPolicy
import com.uhflogger.smartbox.radio.ReaderState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioPolicyTest {

    @Test
    fun `captura BT bloqueia, USB roda em segundo plano, parado roda na frente`() {
        assertEquals(RadioDecision.BLOCK, RadioPolicy.decide(ReaderState.BT_CAPTURING))
        assertEquals(RadioDecision.BLOCK, RadioPolicy.decide(ReaderState.BT_RECONNECTING))
        assertEquals(RadioDecision.BACKGROUND, RadioPolicy.decide(ReaderState.USB_CAPTURING))
        assertEquals(RadioDecision.BACKGROUND, RadioPolicy.decide(ReaderState.USB_RECONNECTING))
        assertEquals(RadioDecision.FOREGROUND, RadioPolicy.decide(ReaderState.IDLE))
    }

    @Test
    fun `BT conectando conta como captura durante a janela do connect`() {
        // startCapture() acabou de ser chamado: isRunning e isPaused ainda false.
        assertEquals(ReaderState.BT_CAPTURING, RadioPolicy.deriveReaderState(false, false, true, 1_000))
        assertEquals(ReaderState.IDLE, RadioPolicy.deriveReaderState(false, false, true, RadioPolicy.BT_CONNECT_GRACE_MS))
        assertEquals(ReaderState.IDLE, RadioPolicy.deriveReaderState(false, false, true, Long.MAX_VALUE))
    }

    @Test
    fun `estado por transporte`() {
        assertEquals(ReaderState.BT_CAPTURING, RadioPolicy.deriveReaderState(true, false, true, Long.MAX_VALUE))
        assertEquals(ReaderState.BT_RECONNECTING, RadioPolicy.deriveReaderState(false, true, true, Long.MAX_VALUE))
        assertEquals(ReaderState.USB_CAPTURING, RadioPolicy.deriveReaderState(true, false, false, 0))
        assertEquals(ReaderState.USB_RECONNECTING, RadioPolicy.deriveReaderState(false, true, false, 0))
        // USB não tem janela de connect: não ocupa o rádio.
        assertEquals(ReaderState.IDLE, RadioPolicy.deriveReaderState(false, false, false, 0))
    }
}

class SmartboxTelemetryTest {

    @Test
    fun `status GATT vem do campo e, se faltar, do texto do erro`() {
        assertEquals(133, SmartboxTelemetry.gattStatus(GattException("connect", 133, "falhou")))
        assertEquals(8, SmartboxTelemetry.gattStatus(LinkBrokenException("caiu", RuntimeException("Device disconnected with status 8"))))
        assertEquals(62, SmartboxTelemetry.gattStatus(GattException("connect", null, "gatt status=62")))
        assertNull(SmartboxTelemetry.gattStatus(RuntimeException("sem código")))
        assertNull(SmartboxTelemetry.gattStatus(null))
    }

    @Test
    fun `nome do erro desembrulha a causa e a mensagem junta as camadas`() {
        val err = LinkBrokenException("A conexão com a smartbox caiu", GattException("disconnected", 8, "status 8"))
        assertEquals("LinkBrokenException<GattException>", SmartboxTelemetry.errorName(err))
        assertEquals("A conexão com a smartbox caiu | status 8", SmartboxTelemetry.errorMessage(err))
        assertEquals("IllegalStateException", SmartboxTelemetry.errorName(IllegalStateException("x")))
    }

    @Test
    fun `failed leva step, tentativas e reader_state`() {
        val base = SmartboxTelemetry.base("48E7299ED5B4", "auto_collect", "background", "usb_capturing", -70, 800)
        val props = SmartboxTelemetry.failed(base, "connect", GattException("connect", 62, "x"), 3, listOf("62", "133", "62"), 31_000)
        assertEquals("connect", props["step"])
        assertEquals(62, props["android_error_code"])
        assertEquals("62,133,62", props["attempt_errors"])
        assertEquals("usb_capturing", props["reader_state"])
        assertEquals("background", props["collect_mode"])
    }

    @Test
    fun `collect yielded diz quanto baixou e quanto faltou`() {
        val p = SmartboxTelemetry.collectYielded("M", "auto_collect", "blocking", "stop_button", 1234, 3766, 1800, false)
        assertEquals(1234, p["downloaded"])
        assertEquals(3766L, p["remaining"])
        assertEquals(false, p["forced_disconnect"])
    }

    @Test
    fun `codigo de falha do scan tem nome`() {
        assertEquals("SCAN_FAILED_SCANNING_TOO_FREQUENTLY", SmartboxTelemetry.scanErrorText(6))
        assertEquals("SCAN_FAILED_99", SmartboxTelemetry.scanErrorText(99))
    }
}

class FarmRulesTest {
    private fun adv(farmId: Long, state: Int = 0, proto: Int = 2) =
        Advertisement(proto, "48E7299ED5B4", state, 0, 10, farmId, 1)

    @Test
    fun `minha fazenda baixa, configura e diagnostica`() {
        val a = FarmRules.allowedActions(adv(85), myFarmId = 85)
        assertTrue(a.download && a.configure && a.diagnose && a.reboot)
        assertFalse(a.register || a.transfer || a.factoryReset)
    }

    @Test
    fun `sem fazenda cadastra mas nao baixa`() {
        val a = FarmRules.allowedActions(adv(0), myFarmId = 85)
        assertTrue(a.register && a.configure && a.diagnose)
        assertFalse(a.download)
    }

    @Test
    fun `outra fazenda nao tem acao nenhuma no UHF Logger`() {
        val a = FarmRules.allowedActions(adv(7), myFarmId = 85)
        assertEquals(FarmRules.Actions(), a)
        // Mesmo em Wi-Fi: só o aviso de que a fazenda vem da chave.
        assertTrue(FarmRules.allowedActions(adv(7, state = 0b100), myFarmId = 85).transferBlockedByWifi)
    }

    @Test
    fun `gestor e padrao de fabrica ficam desligados no UHF Logger`() {
        assertFalse(FarmRules.IS_MANAGER)
        assertFalse(FarmRules.allowedActions(adv(85), 85).factoryReset)
    }

    @Test
    fun `protocolo diferente so pede para atualizar o app`() {
        assertEquals(FarmRules.Actions(needsAppUpdate = true), FarmRules.allowedActions(adv(85, proto = 3), 85))
    }

    @Test
    fun `aparelho sem fazenda selecionada nao mexe em fazenda`() {
        assertEquals(FarmRules.Actions(), FarmRules.allowedActions(adv(0), myFarmId = 0))
    }

    @Test
    fun `fazenda pelo nome e desconhecida pelo codigo`() {
        val farms = listOf(85 to "São José", 90 to "Boa Vista")
        assertEquals("Fazenda São José", FarmLabel.of(85, farms).title)
        assertEquals("da fazenda Boa Vista", FarmLabel.of(90, farms).of)
        assertEquals("Outra fazenda (código 65)", FarmLabel.of(65, farms).title)
        assertEquals("em outra fazenda (código 65)", FarmLabel.of(65, emptyList()).inFarm)
    }
}
