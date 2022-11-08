package vexriscv.demo

import spinal.core._
import vexriscv.VexRiscv

case class VexChipSmpConfig
(cpuCount: Int = 0,
 name: String = "VexChipSmp",
 configs: Seq[VexAxiConfig] = Seq())
object VexChipSmpConfig {
  def default = VexChipSmpConfig()
}

class VexChipSmp(config: VexChipSmpConfig) extends Module {
  import config._
  val cpus: Seq[VexRiscv] = configs.map(config => {
    GenAxi.getCore(config)
  })
}

object VexChipSmpCore {
  def run(config: VexChipSmpConfig): Unit = {
  }
}

object GenVexChipSmp extends App {
  VexChipSmpCore.run(VexChipSmpConfig.default)
}
