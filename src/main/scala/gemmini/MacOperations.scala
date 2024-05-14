package gemmini

import chisel3._
import chisel3.util._

object MacOperations {
  def mac(a: SInt, b: SInt, c: SInt): SInt = {
    // ************ TODO: Start code region here ************
    // Replace this with your own multiplier implementation
    val prod = a * b
    // ************ TODO: End code region here ************
    prod + c
  }
}