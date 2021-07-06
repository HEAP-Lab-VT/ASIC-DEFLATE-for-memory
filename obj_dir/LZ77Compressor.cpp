#include "Vlz77Compressor.h"
#include "verilated.h"
#include "verilated_vcd_c.h"
#include <stdio.h>

#define TRACE_ENABLE true

#define TIMEOUT_ENABLE true
#define TIMEOUT_CYCLES 20000

#define IN_VEC_SIZE 8
#define OUT_VEC_SIZE 8

static size_t min(size_t a, size_t b) {return a <= b ? a : b;}
static size_t max(size_t a, size_t b) {return a >= b ? a : b;}

int main(int argc, char **argv, char **env)
{
	Verilated::commandArgs(argc, argv);
	Vlz77Compressor *compressor = new Vlz77Compressor;
	
#if TRACE_ENABLE
	char trace_enable = argc > 3 && (argv[3][0] != '-' || argv[3][1] != '\0');
	VerilatedVcdC* trace;
	if(trace_enable) {
		Verilated::traceEverOn(true);
		trace = new VerilatedVcdC;
		compressor->trace(trace, 99);
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
	
	compressor->reset = 1;
	compressor->clock = 0;
	compressor->eval();
	compressor->clock = 1;
	compressor->eval();
	compressor->reset = 0;
	
	int cycles = 0;
	do {
		compressor->clock = 1;
		compressor->eval();
		
		size_t bytesRead =
			fread(inBuf + inBufIdx, 1, IN_VEC_SIZE - inBufIdx, inf);
		compressor->io_in_finished = !bytesRead && !inBufIdx;
		inBufIdx += bytesRead;
		compressor->io_in_bits_0 = inBuf[0];
		compressor->io_in_bits_1 = inBuf[1];
		compressor->io_in_bits_2 = inBuf[2];
		compressor->io_in_bits_3 = inBuf[3];
		compressor->io_in_bits_4 = inBuf[4];
		compressor->io_in_bits_5 = inBuf[5];
		compressor->io_in_bits_6 = inBuf[6];
		compressor->io_in_bits_7 = inBuf[7];
		compressor->io_in_valid = inBufIdx;
		compressor->io_out_ready = OUT_VEC_SIZE - outBufIdx;
		
		compressor->eval();
		
		size_t c =
			min(compressor->io_in_valid, compressor->io_in_ready);
		inBufIdx -= c;
		for(int i = 0; i < inBufIdx; i++)
			inBuf[i] = inBuf[i + c];
		
		c = min(compressor->io_out_valid, compressor->io_out_ready);
		char tmpBuf[OUT_VEC_SIZE];
		tmpBuf[0] = compressor->io_out_bits_0;
		tmpBuf[1] = compressor->io_out_bits_1;
		tmpBuf[2] = compressor->io_out_bits_2;
		tmpBuf[3] = compressor->io_out_bits_3;
		tmpBuf[4] = compressor->io_out_bits_4;
		tmpBuf[5] = compressor->io_out_bits_5;
		tmpBuf[6] = compressor->io_out_bits_6;
		tmpBuf[7] = compressor->io_out_bits_7;
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
		
		compressor->clock = 0;
		compressor->eval();
		
#if TRACE_ENABLE
		if(trace_enable) {
			trace->dump(Verilated::time());
			Verilated::timeInc(1);
		}
#endif
	} while(!compressor->io_out_finished
		&& (!TIMEOUT_ENABLE || cycles++ < TIMEOUT_CYCLES));
	
	if(inf != stdin)
		fclose(inf);
	if(outf != stdout)
		fclose(outf);
	
#if TRACE_ENABLE
		if(trace_enable) {
			trace->close();
		}
#endif
	
	delete compressor;
	
	fprintf(stderr, "compressor cycles: %d\n", cycles);
	
	exit(0);
}
