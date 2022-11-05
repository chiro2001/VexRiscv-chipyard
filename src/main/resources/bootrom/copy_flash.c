#include <stdint.h>

#include "address.h"
void copy_flash() {
  uint32_t len = FLASH_SIZE;
  uint32_t *src = (uint32_t *)(FLASH_ADDR);
  uint32_t *dst = (uint32_t *)(DDR_ADDR);
  while (len--) {
    *(dst++) = *(src++);
  }
}