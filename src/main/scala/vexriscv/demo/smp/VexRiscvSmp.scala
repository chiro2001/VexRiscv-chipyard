package vexriscv.demo.smp

import spinal.core._
import spinal.core.fiber._
import spinal.idslplugin.PostInitCallback
import spinal.lib.bus.amba4.axi.{Axi4Config, Axi4Shared}
import spinal.lib.bus.bmb._
import spinal.lib.bus.misc.{AddressMapping, DefaultMapping, SizeMapping}
import spinal.lib.com.jtag.JtagInstructionDebuggerGenerator
import spinal.lib.generator._
import spinal.lib.master
import vexriscv.ip.fpu.FpuParameter
import vexriscv.ip.{DataCacheConfig, InstructionCacheConfig}
import vexriscv.plugin._
import vexriscv.{Riscv, VexRiscvBmbGenerator, VexRiscvConfig, plugin}

import scala.language.postfixOps

case class VexRiscvSmpParameter
(cpuConfigs: Seq[VexRiscvConfig],
 withExclusiveAndInvalidation: Boolean,
 jtagHeaderIgnoreWidth: Int = 0,
 forcePeripheralWidth: Boolean = true,
 outOfOrderDecoder: Boolean = true,
 fpu: Boolean = false)

class VexRiscvSmpBase(p: VexRiscvSmpParameter, enableDebug: Boolean = false) extends Area with PostInitCallback {
  val cpuCount = p.cpuConfigs.size

  val debugCd = if (enableDebug) {
    val debugCd = ClockDomainResetGenerator()
    debugCd.holdDuration.load(4095)
    debugCd.makeExternal()
    debugCd
  } else null

  val systemCd = if (enableDebug) {
    val systemCd = ClockDomainResetGenerator()
    systemCd.holdDuration.load(63)
    systemCd.setInput(debugCd)
    systemCd
  } else {
    val systemCd = ClockDomainResetGenerator()
    systemCd.holdDuration.load(63)
    systemCd.makeExternal()
    systemCd
  }


  val ctx = systemCd.outputClockDomain.push()

  override def postInitCallback(): VexRiscvSmpBase.this.type = {
    ctx.restore()
    this
  }

  implicit val interconnect = BmbInterconnectGenerator()

  // val debugBridge = debugCd.outputClockDomain on JtagInstructionDebuggerGenerator(p.jtagHeaderIgnoreWidth)
  // debugBridge.jtagClockDomain.load(ClockDomain.external("jtag", withReset = false))
  //
  // val debugPort = Handle(debugBridge.logic.jtagBridge.io.ctrl.toIo)

  val dBusCoherent = BmbBridgeGenerator()
  val dBusNonCoherent = BmbBridgeGenerator()

  val smp = p.withExclusiveAndInvalidation generate new Area {
    val exclusiveMonitor = BmbExclusiveMonitorGenerator()
    interconnect.addConnection(dBusCoherent.bmb, exclusiveMonitor.input)

    val invalidationMonitor = BmbInvalidateMonitorGenerator()
    interconnect.addConnection(exclusiveMonitor.output, invalidationMonitor.input)
    interconnect.addConnection(invalidationMonitor.output, dBusNonCoherent.bmb)
    if (p.outOfOrderDecoder) interconnect.masters(invalidationMonitor.output).withOutOfOrderDecoder()
  }

  val noSmp = !p.withExclusiveAndInvalidation generate new Area {
    interconnect.addConnection(dBusCoherent.bmb, dBusNonCoherent.bmb)
  }

  val cores = for (cpuId <- 0 until cpuCount) yield new Area {
    val cpu = VexRiscvBmbGenerator()
    cpu.config.load(p.cpuConfigs(cpuId))
    interconnect.addConnection(
      cpu.dBus -> List(dBusCoherent.bmb)
    )
    // cpu.enableDebugBmb(
    //   debugCd = debugCd.outputClockDomain,
    //   resetCd = systemCd,
    //   mapping = SizeMapping(cpuId * 0x1000, 0x1000)
    // )
    cpu.disableDebug()
    // interconnect.addConnection(debugBridge.bmb, cpu.debugBmb)
  }
}

case class BmbToAxiSharedGenerator(mapping: AddressMapping)(implicit interconnect: BmbInterconnectGenerator) extends Area {
  val bmb = Handle(logic.io.input)
  val axiShared = Handle(logic.io.output)

  val accessSource = Handle[BmbAccessCapabilities]
  val accessRequirements = Handle[BmbAccessParameter]
  interconnect.addSlave(
    accessSource = accessSource,
    accessCapabilities = accessSource,
    accessRequirements = accessRequirements,
    bus = bmb,
    mapping = mapping
  )
  val logic = Handle(BmbToAxi4SharedBridge(
    accessRequirements.toBmbParameter()
  ))
}


class VexRiscvSmp(p: VexRiscvSmpParameter, enableDebug: Boolean = false)
  extends VexRiscvSmpBase(p, enableDebug = enableDebug) {
  val axiBridge = BmbToAxiSharedGenerator(DefaultMapping)
  val dBus: Axi4Shared = Handle(axiBridge.logic.io.output.toIo)
  // if (p.forcePeripheralWidth) interconnect.slaves(axiBridge.bmb).forceAccessSourceDataWidth(32)

  val iArbiter = BmbBridgeGenerator()

  for (core <- cores) interconnect.addConnection(core.cpu.iBus -> List(iArbiter.bmb))
  interconnect.addConnection(
    iArbiter.bmb -> List(axiBridge.bmb),
    dBusNonCoherent.bmb -> List(axiBridge.bmb)
  )
  for (core <- cores) {
    interconnect.setPipelining(core.cpu.dBus)(cmdValid = true, cmdReady = true, rspValid = true, invValid = true, ackValid = true, syncValid = true)
    interconnect.setPipelining(core.cpu.iBus)(cmdHalfRate = true, rspValid = true)
    interconnect.setPipelining(iArbiter.bmb)(cmdHalfRate = true, rspValid = true)
  }
  interconnect.setPipelining(dBusCoherent.bmb)(cmdValid = true, cmdReady = true)
  interconnect.setPipelining(dBusNonCoherent.bmb)(cmdValid = true, cmdReady = true, rspValid = true)
  interconnect.setPipelining(axiBridge.bmb)(cmdHalfRate = true, cmdValid = false, cmdReady = false, rspValid = true)

  val timerInterrupt = in UInt (p.cpuConfigs.size bits)
  val softwareInterrupt = in UInt (p.cpuConfigs.size bits)
  val externalInterrupt = in UInt (p.cpuConfigs.size bits)
  val externalSupervisorInterrupt = in UInt (p.cpuConfigs.size bits)
  val utime = in UInt ((64 * p.cpuConfigs.size) bits)


  for ((core, cpuId) <- cores.zipWithIndex) {
    core.cpu.setTimerInterrupt(timerInterrupt(cpuId))
    core.cpu.setSoftwareInterrupt(softwareInterrupt(cpuId))
    Handle(new Area {
      core.cpu.externalInterrupt := externalInterrupt(cpuId)
      core.cpu.externalSupervisorInterrupt := externalInterrupt(cpuId)
      for (plugin <- core.cpu.config.plugins) plugin match {
        case plugin: CsrPlugin =>
          if (plugin.utime != null) plugin.utime := utime(((cpuId + 1) * 64 - 1) downto (cpuId * 64))
          else println(s"null utime code id $cpuId")
        case _ =>
      }
    })
  }
}

object VexRiscvSmpGen {
  def vexRiscvConfig
  (hartId: Int,
   ioRange: UInt => Bool = (x => x(31 downto 28) === 0xF),
   resetVector: Long = 0x80000000l,
   iBusWidth: Int = 128,
   dBusWidth: Int = 64,
   loadStoreWidth: Int = 32,
   coherency: Boolean = true,
   atomic: Boolean = true,
   iCacheSize: Int = 8192,
   dCacheSize: Int = 8192,
   iCacheWays: Int = 2,
   dCacheWays: Int = 2,
   iBusRelax: Boolean = false,
   injectorStage: Boolean = false,
   earlyBranch: Boolean = false,
   earlyShifterInjection: Boolean = true,
   dBusCmdMasterPipe: Boolean = false,
   withMmu: Boolean = true,
   withSupervisor: Boolean = true,
   withFloat: Boolean = false,
   withDouble: Boolean = false,
   externalFpu: Boolean = true,
   simHalt: Boolean = false,
   decoderIsolationBench: Boolean = false,
   decoderStupid: Boolean = false,
   regfileRead: RegFileReadKind = plugin.ASYNC,
   rvc: Boolean = false,
   iTlbSize: Int = 4,
   dTlbSize: Int = 4,
   prediction: BranchPrediction = vexriscv.plugin.NONE,
   withDataCache: Boolean = true,
   withInstructionCache: Boolean = true,
   forceMisa: Boolean = false,
   forceMscratch: Boolean = false
  ) = {
    assert(iCacheSize / iCacheWays <= 4096, "Instruction cache ways can't be bigger than 4096 bytes")
    assert(dCacheSize / dCacheWays <= 4096, "Data cache ways can't be bigger than 4096 bytes")
    assert(!(withDouble && !withFloat))

    val csrConfig = if (withSupervisor) {
      CsrPluginConfig.openSbi(mhartid = hartId, misa = Riscv.misaToInt(s"ima${if (withFloat) "f" else ""}${if (withDouble) "d" else ""}s")).copy(utimeAccess = CsrAccess.READ_ONLY)
    } else {
      CsrPluginConfig(
        catchIllegalAccess = true,
        mvendorid = null,
        marchid = null,
        mimpid = null,
        mhartid = hartId,
        misaExtensionsInit = Riscv.misaToInt(s"ima${if (withFloat) "f" else ""}${if (withDouble) "d" else ""}s"),
        misaAccess = if (forceMisa) CsrAccess.WRITE_ONLY else CsrAccess.NONE,
        mtvecAccess = CsrAccess.READ_WRITE,
        mtvecInit = null,
        mepcAccess = CsrAccess.READ_WRITE,
        mscratchGen = forceMscratch,
        mcauseAccess = CsrAccess.READ_ONLY,
        mbadaddrAccess = CsrAccess.READ_ONLY,
        mcycleAccess = CsrAccess.NONE,
        minstretAccess = CsrAccess.NONE,
        ecallGen = true,
        ebreakGen = true,
        wfiGenAsWait = false,
        wfiGenAsNop = true,
        ucycleAccess = CsrAccess.NONE
      )
    }
    val config = VexRiscvConfig(
      plugins = List(
        if (withMmu) new MmuPlugin(
          ioRange = ioRange
        ) else new StaticMemoryTranslatorPlugin(
          ioRange = ioRange
        ),
        //Uncomment the whole IBusCachedPlugin and comment IBusSimplePlugin if you want cached iBus config
        if (withInstructionCache) new IBusCachedPlugin(
          resetVector = resetVector,
          compressedGen = rvc,
          prediction = prediction,
          historyRamSizeLog2 = 9,
          relaxPredictorAddress = true,
          injectorStage = injectorStage,
          relaxedPcCalculation = iBusRelax,
          config = InstructionCacheConfig(
            cacheSize = iCacheSize,
            bytePerLine = 64,
            wayCount = iCacheWays,
            addressWidth = 32,
            cpuDataWidth = 32,
            memDataWidth = iBusWidth,
            catchIllegalAccess = true,
            catchAccessFault = true,
            asyncTagMemory = false,
            twoCycleRam = false,
            twoCycleCache = true,
            reducedBankWidth = true
          ),
          memoryTranslatorPortConfig = MmuPortConfig(
            portTlbSize = iTlbSize,
            latency = 1,
            earlyRequireMmuLockup = true,
            earlyCacheHits = true
          )
        ) else new IBusSimplePlugin(
          resetVector = resetVector,
          cmdForkOnSecondStage = false,
          cmdForkPersistence = false,
          prediction = NONE,
          catchAccessFault = false,
          compressedGen = rvc,
          busLatencyMin = 2,
          vecRspBuffer = true
        ),
        if (withDataCache) new DBusCachedPlugin(
          dBusCmdMasterPipe = dBusCmdMasterPipe || dBusWidth == 32,
          dBusCmdSlavePipe = true,
          dBusRspSlavePipe = true,
          relaxedMemoryTranslationRegister = true,
          config = new DataCacheConfig(
            cacheSize = dCacheSize,
            bytePerLine = 64,
            wayCount = dCacheWays,
            addressWidth = 32,
            cpuDataWidth = loadStoreWidth,
            memDataWidth = dBusWidth,
            catchAccessError = true,
            catchIllegal = true,
            catchUnaligned = true,
            withLrSc = atomic,
            withAmo = atomic,
            withExclusive = coherency,
            withInvalidate = coherency,
            withWriteAggregation = dBusWidth > 32
          ),
          memoryTranslatorPortConfig = MmuPortConfig(
            portTlbSize = dTlbSize,
            latency = 1,
            earlyRequireMmuLockup = true,
            earlyCacheHits = true
          )
        ) else new DBusSimplePlugin(
          catchAddressMisaligned = false,
          catchAccessFault = false,
          earlyInjection = false
        ),
        new DecoderSimplePlugin(
          catchIllegalInstruction = true,
          decoderIsolationBench = decoderIsolationBench,
          stupidDecoder = decoderStupid
        ),
        new RegFilePlugin(
          regFileReadyKind = regfileRead,
          zeroBoot = false,
          x0Init = true
        ),
        new IntAluPlugin,
        new SrcPlugin(
          separatedAddSub = false
        ),
        new FullBarrelShifterPlugin(earlyInjection = earlyShifterInjection),
        //        new LightShifterPlugin,
        new HazardSimplePlugin(
          bypassExecute = true,
          bypassMemory = true,
          bypassWriteBack = true,
          bypassWriteBackBuffer = true,
          pessimisticUseSrc = false,
          pessimisticWriteRegFile = false,
          pessimisticAddressMatch = false
        ),
        new MulPlugin,
        new MulDivIterativePlugin(
          genMul = false,
          genDiv = true,
          mulUnrollFactor = 32,
          divUnrollFactor = 1
        ),
        new CsrPlugin(csrConfig),
        new BranchPlugin(
          earlyBranch = earlyBranch,
          catchAddressMisaligned = true,
          fenceiGenAsAJump = false
        ),
        new YamlPlugin(s"cpu$hartId.yaml")
      )
    )

    if (withFloat) config.plugins += new FpuPlugin(
      externalFpu = externalFpu,
      simHalt = simHalt,
      p = FpuParameter(withDouble = withDouble)
    )
    config
  }

  def run(): Unit = {
    def dutGen = {
      val cpuCount = 2
      val enableDebug = false
      val parameter = VexRiscvSmpParameter(
        cpuConfigs = List.tabulate(cpuCount) {
          vexRiscvConfig(_,
            resetVector = 0x80000000L,
            ioRange = _ (31 downto 28) =/= 0x8,
            iBusWidth = 64,
            dBusWidth = 64,
            iCacheSize = 4096,
            iCacheWays = 1,
            dCacheSize = 4096,
            dCacheWays = 1,
            prediction = DYNAMIC_TARGET
          )
        }, withExclusiveAndInvalidation = true
      )
      class VexCoreSmp extends Component {
        val body = new VexRiscvSmp(
          p = parameter, enableDebug = enableDebug
        )
        body.setName("")
      }
      new VexCoreSmp
    }

    SpinalConfig().generateVerilog(dutGen)
  }

  def main(args: Array[String]): Unit = {
    run()
  }
}
