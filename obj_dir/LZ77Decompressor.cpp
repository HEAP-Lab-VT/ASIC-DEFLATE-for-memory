#include "VLZ77Decompressor.h"
#include "verilated.h"
#include "verilated_vcd_c.h"
#include <stdio.h>

#define TRACE_ENABLE true

#define TIMEOUT_ENABLE true
#define TIMEOUT_CYCLES 20000

#define IN_VEC_SIZE 5
#define OUT_VEC_SIZE 8

static size_t min(size_t a, size_t b) {return a <= b ? a : b;}
static size_t max(size_t a, size_t b) {return a >= b ? a : b;}

int main(int argc, char **argv, char **env)
{
	Verilated::commandArgs(argc, argv);
	VLZ77Decompressor *decompressor = new VLZ77Decompressor;
	
#if TRACE_ENABLE
	char trace_enable = argc > 3 && (argv[3][0] != '-' || argv[3][1] != '\0');
	VerilatedVcdC* trace;
	if(trace_enable) {
		Verilated::traceEverOn(true);
		trace = new VerilatedVcdC;
		decompressor->trace(trace, 99);
		trace->open(argv[3]);
	}
#endif
	
	FILE *inf = stdin;
	FILE *outf = stdout;
	if(argc > 1 && (argv[1][0] != '-' || argv[1][1] != '\0'))
		inf = fopen(argv[1], "r");
	if(argc > 2 && (argv[2][0] != '-' || argv[2][1] != '\0'))
		outf = fopen(argv[2], "w");
	
	char inBuf[IN_VEC_SIZE];
	size_t inBufIdx = 0;
	char outBuf[OUT_VEC_SIZE];
	size_t outBufIdx = 0;
	
	decompressor->reset = 1;
	decompressor->clock = 0;
	decompressor->eval();
	decompressor->clock = 1;
	decompressor->eval();
	decompressor->reset = 0;
	decompressor->io_in_finished = 0;
	
	int cycles = 0;
	int timeout = TIMEOUT_CYCLES;
	do {
		decompressor->clock = 1;
		decompressor->eval();
		
		size_t bytesRead =
			fread(inBuf + inBufIdx, 1, IN_VEC_SIZE - inBufIdx, inf);
		decompressor->io_in_finished = (!bytesRead && IN_VEC_SIZE - inBufIdx)
			|| decompressor->io_in_finished;
		inBufIdx += bytesRead;
		decompressor->io_in_bits_0 = inBuf[0];
		decompressor->io_in_bits_1 = inBuf[1];
		decompressor->io_in_bits_2 = inBuf[2];
		decompressor->io_in_bits_3 = inBuf[3];
		decompressor->io_in_bits_4 = inBuf[4];
		decompressor->io_in_valid = inBufIdx;
		decompressor->io_out_ready = OUT_VEC_SIZE - outBufIdx;
		
		decompressor->eval();
		
		size_t c =
			min(decompressor->io_in_valid, decompressor->io_in_ready);
		inBufIdx -= c;
		for(int i = 0; i < inBufIdx; i++)
			inBuf[i] = inBuf[i + c];
		
		if(c) timeout = TIMEOUT_CYCLES;
		
		c = min(decompressor->io_out_valid, decompressor->io_out_ready);
		char tmpBuf[OUT_VEC_SIZE];
		tmpBuf[0] = decompressor->io_out_bits_0;
		tmpBuf[1] = decompressor->io_out_bits_1;
		tmpBuf[2] = decompressor->io_out_bits_2;
		tmpBuf[3] = decompressor->io_out_bits_3;
		tmpBuf[4] = decompressor->io_out_bits_4;
		tmpBuf[5] = decompressor->io_out_bits_5;
		tmpBuf[6] = decompressor->io_out_bits_6;
		tmpBuf[7] = decompressor->io_out_bits_7;
		for(int i = 0; i < c; i++)
			outBuf[i + outBufIdx] = tmpBuf[i];
		outBufIdx += c;
		
		c = fwrite(outBuf, 1, outBufIdx, outf);
		for(int i = c; i < outBufIdx; i++)
			outBuf[i + c] = outBuf[i];
		outBufIdx -= c;
		
		
#if TRACE_ENABLE
		if(trace_enable) {
			trace->dump(Verilated::time());
			Verilated::timeInc(1);
		}
#endif
		
		decompressor->clock = 0;
		decompressor->eval();
		
#if TRACE_ENABLE
		if(trace_enable) {
			trace->dump(Verilated::time());
			Verilated::timeInc(1);
		}
#endif
		cycles++;
	} while((!decompressor->io_out_finished || outBufIdx)
		&& (!TIMEOUT_ENABLE || --timeout));
	
	if(inf != stdin)
		fclose(inf);
	if(outf != stdout)
		fclose(outf);
		
#if TRACE_ENABLE
		if(trace_enable) {
			trace->close();
		}
#endif
	
	delete decompressor;
	
	if(timeout)
		fprintf(stderr, "decompressor cycles: %d\n", cycles);
	else
		fprintf(stderr, "decompressor cycles: %d (timeout)\n", cycles);
	
	exit(0);
}
