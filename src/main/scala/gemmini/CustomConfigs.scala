
package gemmini

import org.chipsalliance.cde.config.{Config, Parameters}
import chisel3._
import freechips.rocketchip.diplomacy.LazyModule
import freechips.rocketchip.subsystem.SystemBusKey
import freechips.rocketchip.tile.BuildRoCC


object GemminiCustomConfigs {
  // Default configurations
  val defaultConfig = GemminiConfigs.defaultConfig
  val defaultFpConfig = GemminiFPConfigs.defaultFPConfig

  // Create your own configs here
  val baselineInferenceConfig = defaultConfig.copy(
    has_training_convs = false,
  )

  val highPerfInferenceConfig = defaultConfig.copy(
    meshRows = 32,
    meshColumns = 32,

    has_training_convs = false,

    sp_capacity = CapacityInKilobytes(512),
    acc_capacity = CapacityInKilobytes(128),
  )

  val trainingConfig = defaultFpConfig.copy(
    inputType = Float(expWidth = 8, sigWidth = 24),
    accType = Float(expWidth = 8, sigWidth = 24),

    meshRows = 8,
    meshColumns = 8,

    has_training_convs = true,
    has_max_pool =  false,

    sp_capacity = CapacityInKilobytes(512),
    acc_capacity = CapacityInKilobytes(128),
  )

  val ibertInferenceConfig = defaultConfig.copy(
    has_training_convs = false,
    has_max_pool =  false,
    has_normalizations = true,

    acc_capacity = CapacityInKilobytes(128),
  )

  val dseInferenceConfig = baselineInferenceConfig.copy(
    // ************ TODO: Start code region here ************
    // Change these params 
    // Data types
    inputType = SInt(8.W),
    accType = SInt(32.W),
    spatialArrayOutputType = SInt(20.W),

    // Spatial array PE options
    tileRows = 1,
    tileColumns = 1,
    meshRows = 16,
    meshColumns = 16,

    // Dataflow
    dataflow = Dataflow.BOTH, // OS, WS, BOTH

    // Scratchpad and accumulator
    sp_capacity = CapacityInKilobytes(256),
    acc_capacity = CapacityInKilobytes(64),
    sp_banks = 4,
    acc_banks = 2,

    // Reservation station entries
    reservation_station_entries_ld = 8,
    reservation_station_entries_st = 4,
    reservation_station_entries_ex = 16,

    // Ld/Ex/St instruction queue lengths
    ld_queue_length = 8,
    st_queue_length = 2,
    ex_queue_length = 8,

    // DMA options
    max_in_flight_mem_reqs = 16,
    dma_maxbytes = 64,
    dma_buswidth = 128,

    // TLB options
    tlb_size = 4,
    // ************ TODO: End code region here ************
  )

  // Specify which of your custom configs you want to build here
  val customConfig = dseInferenceConfig
}


class GemminiCustomConfig[T <: Data : Arithmetic, U <: Data, V <: Data](
  gemminiConfig: GemminiArrayConfig[T,U,V] = GemminiCustomConfigs.customConfig
) extends Config((site, here, up) => {
  case BuildRoCC => up(BuildRoCC) ++ Seq(
    (p: Parameters) => {
      implicit val q = p
      val gemmini = LazyModule(new Gemmini(gemminiConfig))
      gemmini
    }
  )
})

