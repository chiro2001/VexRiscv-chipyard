bootrom_elf = bootrom.rv32.elf bootrom.sim.rv32.elf bootrom.rom.rv32.elf
bootrom_img = bootrom.rv32.img bootrom.sim.rv32.img bootrom.rom.rv32.img
bootrom_dump = bootrom.rv32.dump bootrom.sim.rv32.dump bootrom.rom.rv32.dump

COREMARK_MAKE ?= $(abspath .)/../../../../../../software/coremark/riscv-coremark/perfN-rom
COREMARK ?= $(COREMARK_MAKE)/../../overlay/coremark.perfN.rom.bin

CROSS_COMPILE := riscv-none-embed-
GCC=$(CROSS_COMPILE)gcc
CFLAGS_RV32=-mabi=ilp32 -march=rv32im
OBJCOPY=$(CROSS_COMPILE)objcopy
OBJDUMP=$(CROSS_COMPILE)objdump

default: img dump elf

img: $(bootrom_img)

dump: $(bootrom_dump)

elf: $(bootrom_elf)

%.img: %.elf
	$(OBJCOPY) -O binary --change-addresses=-0x10000 $< $@

%.rv32.elf: %.S linker.ld
	$(GCC) $(CFLAGS_RV32) -Tlinker.ld $< -nostdlib -static -o $@

%.sim.rv32.elf: %.sim.S linker.sim.ld
	$(GCC) $(CFLAGS_RV32) -Tlinker.sim.ld $< -nostdlib -static -o $@

%.dump: %.elf
	$(OBJDUMP) -d -S $< > $@

%.rom.rv32.img: %.rom.rv32.elf
	$(OBJCOPY) -O binary --change-addresses=-0x10000 $< $@
	$(MAKE) -C $(COREMARK_MAKE)
	dd if=$(COREMARK) of=$@ bs=384 seek=1 conv=notrunc

clean:
	-rm -f *.elf *.dump *.img
