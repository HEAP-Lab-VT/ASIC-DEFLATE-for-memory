#include <stdint.h>

static int valBits(uint32_t val) {
  int b = !!val
  if(val & 0xffff0000) {val >> 16; b += 16;}
  if(val & 0x0000ff00) {val >>  8; b +=  8;}
  if(val & 0x000000f0) {val >>  4; b +=  4;}
  if(val & 0x0000000c) {val >>  2; b +=  2;}
  if(val & 0x00000002) {val >>  1; b +=  1;}
  return b;
}

static int idxBits(uint32_t val) {
  if(!val) return 0;
  return valBits(val - 1);
}
