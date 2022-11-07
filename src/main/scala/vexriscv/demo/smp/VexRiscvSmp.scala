package vexriscv.demo.smp

import spinal.core
import spinal.core._
import spinal.core.sim.{onSimEnd, simSuccess}
import spinal.lib._
import spinal.lib.bus.bmb.sim.BmbMemoryAgent
import spinal.lib.bus.bmb._
import spinal.lib.bus.misc.{DefaultMapping, SizeMapping}
import spinal.lib.bus.wishbone.{Wishbone, WishboneConfig, WishboneToBmb, WishboneToBmbGenerator}
import spinal.lib.com.jtag.{Jtag, JtagInstructionDebuggerGenerator, JtagTapInstructionCtrl}
import spinal.lib.com.jtag.sim.JtagTcp
import spinal.lib.com.jtag.xilinx.Bscane2BmbMasterGenerator
import spinal.lib.generator._
import spinal.core.fiber._
import spinal.idslplugin.PostInitCallback
import spinal.lib.misc.plic.PlicMapping
import spinal.lib.system.debugger.SystemDebuggerConfig
import vexriscv.ip.{DataCacheAck, DataCacheConfig, DataCacheMemBus, InstructionCache, InstructionCacheConfig}
import vexriscv.plugin._
import vexriscv.{Riscv, VexRiscv, VexRiscvBmbGenerator, VexRiscvConfig, plugin}

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import spinal.lib.generator._
import vexriscv.ip.fpu.FpuParameter

case class VexRiscvSmpParameter
(cpuConfigs: Seq[VexRiscvConfig],
 jtagHeaderIgnoreWidth: Int,
 withExclusiveAndInvalidation: Boolean,
 forcePeripheralWidth: Boolean = true,
 outOfOrderDecoder: Boolean = true,
 fpu: Boolean = false)

class VexRiscvSmpBase(p: VexRiscvSmpParameter) extends Area with PostInitCallback {
  val cpuCount = p.cpuConfigs.size

  val debugCd = ClockDomainResetGenerator()
  debugCd.holdDuration.load(4095)
  debugCd.makeExternal()

  val systemCd = ClockDomainResetGenerator()
  systemCd.holdDuration.load(63)
  systemCd.setInput(debugCd)


  val ctx = systemCd.outputClockDomain.push()

  override def postInitCallback(): VexRiscvSmpBase.this.type = {
    ctx.restore()
    this
  }

  implicit val interconnect = BmbInterconnectGenerator()

  val debugBridge = debugCd.outputClockDomain on JtagInstructionDebuggerGenerator(p.jtagHeaderIgnoreWidth)
  debugBridge.jtagClockDomain.load(ClockDomain.external("jtag", withReset = false))

  val debugPort = Handle(debugBridge.logic.jtagBridge.io.ctrl.toIo)

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
    cpu.enableDebugBmb(
      debugCd = debugCd.outputClockDomain,
      resetCd = systemCd,
      mapping = SizeMapping(cpuId * 0x1000, 0x1000)
    )
    interconnect.addConnection(debugBridge.bmb, cpu.debugBmb)
  }
}


class VexRiscvSmpWithPeripherals(p: VexRiscvSmpParameter) extends VexRiscvSmpBase(p) {
  val peripheralBridge = BmbToWishboneGenerator(DefaultMapping)
  val peripheral = Handle(peripheralBridge.logic.io.output.toIo)
  if (p.forcePeripheralWidth) interconnect.slaves(peripheralBridge.bmb).forceAccessSourceDataWidth(32)

  val plic = BmbPlicGenerator()(interconnect = null)
  plic.priorityWidth.load(2)
  plic.mapping.load(PlicMapping.sifive)

  val plicWishboneBridge = new Generator {
    dependencies += plic.ctrl

    plic.accessRequirements.load(BmbAccessParameter(
      addressWidth = 22,
      dataWidth = 32
    ).addSources(1, BmbSourceParameter(
      contextWidth = 0,
      lengthWidth = 2,
      alignment = BmbParameter.BurstAlignement.LENGTH
    )))

    val logic = add task new Area {
      val bridge = WishboneToBmb(WishboneConfig(20, 32))
      bridge.io.output >> plic.ctrl
    }
  }
  val plicWishbone = plicWishboneBridge.produceIo(plicWishboneBridge.logic.bridge.io.input)

  val clint = BmbClintGenerator(0)(interconnect = null)
  val clintWishboneBridge = new Generator {
    dependencies += clint.ctrl

    clint.accessRequirements.load(BmbAccessParameter(
      addressWidth = 16,
      dataWidth = 32
    ).addSources(1, BmbSourceParameter(
      contextWidth = 0,
      lengthWidth = 2,
      alignment = BmbParameter.BurstAlignement.LENGTH
    )))

    val logic = add task new Area {
      val bridge = WishboneToBmb(WishboneConfig(14, 32))
      bridge.io.output >> clint.ctrl
    }
  }
  val clintWishbone = clintWishboneBridge.produceIo(clintWishboneBridge.logic.bridge.io.input)

  val interrupts = in Bits (32 bits)
  for (i <- 1 to 31) yield plic.addInterrupt(interrupts(i), i)

  for ((core, cpuId) <- cores.zipWithIndex) {
    core.cpu.setTimerInterrupt(clint.timerInterrupt(cpuId))
    core.cpu.setSoftwareInterrupt(clint.softwareInterrupt(cpuId))
    plic.priorityWidth.load(2)
    plic.mapping.load(PlicMapping.sifive)
    plic.addTarget(core.cpu.externalInterrupt)
    plic.addTarget(core.cpu.externalSupervisorInterrupt)
    List(clint.logic, core.cpu.logic).produce {
      for (plugin <- core.cpu.config.plugins) plugin match {
        case plugin: CsrPlugin if plugin.utime != null => plugin.utime := clint.logic.io.time
        case _ =>
      }
    }
  }

  clint.cpuCount.load(cpuCount)
}

class VexRiscvSmp(p: VexRiscvSmpParameter) extends VexRiscvSmpBase(p)

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


  // def vexRiscvCluster(cpuCount: Int, resetVector: Long = 0x80000000l) = new VexRiscvSmp(
  //   // debugClockDomain = ClockDomain.current.copy(reset = Bool().setName("debugResetIn")),
  //   p = VexRiscvSmpParameter(
  //     cpuConfigs = List.tabulate(cpuCount) {
  //       vexRiscvConfig(_, resetVector = resetVector)
  //     }
  //   )
  // )
  //
  // def main(args: Array[String]): Unit = {
  //   SpinalVerilog {
  //     vexRiscvCluster(4)
  //   }
  // }
}
