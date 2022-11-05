#include "address.h"

.section .start, "ax", @progbits
.global _start
_start:
  li gp, __STACKSIZE__
  li sp, __STACKSIZE__
  # jal copy_flash
  li a0, BOOTADDR_REG
  lw a1, 0(a0)
  csrw mepc, a0       // return from interrupt to start of user program
  csrr a0, mhartid    // hartid for next level bootloader
  la a1, _dtb         // dtb address for next level bootloader
  li a2, 0x80         // set mstatus MPIE to 0
  csrc mstatus, a2
  mret

.align 3
_dtb:
