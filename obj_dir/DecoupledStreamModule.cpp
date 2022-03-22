#include "verilated.h"
#include <stdio.h>

// <editor-fold> ugly pre-processor macros
#define _STR(s) #s
#define STR(s) _STR(s)
#define _CAT(s,t) s##t
#define CAT(s,t) _CAT(s,t)
// </editor-fold>

#ifndef MODNAME
// #define MODNAME LZCompressor
#endif
#define VMODNAME CAT(V,MODNAME)
#include STR(VMODNAME.h)

#ifndef TRACE_ENABLE
#define TRACE_ENABLE false
#endif

#if TRACE_ENABLE
#include "verilated_vcd_c.h"
#endif

#ifndef IN_CHARS
#define IN_CHARS 8
#endif
#ifndef OUT_CHARS
#define OUT_CHARS 8
#endif

#ifndef YUQING_MODE
#define YUQING_MODE false
#endif

#ifndef TIMEOUT
#define TIMEOUT (idle >= 1000)
#endif

static size_t min(size_t a, size_t b) {return a <= b ? a : b;}
static size_t max(size_t a, size_t b) {return a >= b ? a : b;}


#if !YUQING_MODE
int main(int argc, char **argv, char **env)
#else
int MODNAME(const char *input, size_t inlen, char *output, size_t *outlen,
	const char *tracefile)
#endif
{
	Verilated::commandArgs(argc, argv);
	VMODNAME *module = new VMODNAME;
	
#if TRACE_ENABLE
#if !YUQING_MODE
	char trace_enable = argc > 3 && (argv[3][0] != '-' || argv[3][1] != '\0');
#else
	char trace_enable = tracefile != NULL && tracefile[0] != '\0' &&
		(tracefile[0] != '-' || tracefile[1] != '\0');
#endif
	VerilatedVcdC* trace;
	if(trace_enable) {
		Verilated::traceEverOn(true);
		trace = new VerilatedVcdC;
		module->trace(trace, 99);
#if !YUQING_MODE
		trace->open(argv[3]);
#else
		trace->open(tracefile);
#endif
	}
#endif
	
#if !YUQING_MODE
	FILE *inf = stdin;
	FILE *outf = stdout;
	if(argc > 1 && (argv[1][0] != '-' || argv[1][1] != '\0'))
		inf = fopen(argv[1], "r");
	if(argc > 2 && (argv[2][0] != '-' || argv[2][1] != '\0'))
		outf = fopen(argv[2], "w");
#else
	size_t outbuflen = *outlen;
	*outlen = 0;
#endif
	
	char inBuf[IN_CHARS];
	size_t inBufIdx = 0;
	char outBuf[OUT_CHARS];
	size_t outBufIdx = 0;
	
	// assert reset on rising edge to initialize module state
	module->reset = 1;
	module->clock = 0;
	module->eval();
	module->clock = 1;
	module->eval();
	module->reset = 0;
	module->io_in_last = 0;
	
	int cycles = 0;
	int idle = 0;
	do {
		// update module registers with rising edge
		module->clock = 1;
		module->eval();
		
		// read bytes from input stream to input buffer
#if !YUQING_MODE
		size_t bytesRead = fread(inBuf + inBufIdx, 1, IN_CHARS - inBufIdx, inf);
		module->io_in_last = module->io_in_last || feof(inf);
		inBufIdx += bytesRead;
#else
		while(true) {
			if(!inlen) {
				module->io_in_last = true;
				break;
			}
			else if(IN_CHARS == inBufIdx) {
				break;
			}
			*(inBuf + inBufIdx) = *input;
			inBufIdx++;
			input++;
			inlen--;
		}
#endif
		
		// expose input buffer to module
		// module input is not in array form, so must use ugly cast
		for(int i = 0; i < IN_CHARS; i++) {
			(&module->io_in_data_0)[i] = inBuf[i]; // very ugly cast
		}
		
		// assert valid input count and ready output count
		module->io_in_valid = inBufIdx;
		module->io_out_ready = OUT_CHARS - outBufIdx;
		
		// update module outputs based on new inputs
		module->eval();
		
		idle++;
		
		// shift input buffer by number of characters consumed by module input
		size_t c = min(module->io_in_valid, module->io_in_ready);
		if(c) idle = 0;
		inBufIdx -= c;
		for(int i = 0; i < inBufIdx; i++)
			inBuf[i] = inBuf[i + c];
		
		// push module output onto the end of output buffer
		// module output is not in array form, so must use ugly cast
		c = min(module->io_out_valid, module->io_out_ready);
		if(c) idle = 0;
		for(int i = 0; i < c; i++)
			outBuf[i + outBufIdx] = (&module->io_out_data_0)[i]; // very ugly cast
		outBufIdx += c;
		
		// write output buffer to output stream
#if !YUQING_MODE
		c = fwrite(outBuf, 1, outBufIdx, outf);
#else
		for(c = 0; c < outBufIdx && *outlen < outbuflen; c++) {
			output[outlen] = outBuf[c];
			*outlen++;
		}
		c = outBufIdx;
#endif
		for(int i = c; i < outBufIdx; i++)
			outBuf[i + c] = outBuf[i];
		outBufIdx -= c;
		
#if TRACE_ENABLE
		// put all the important information in the trace graph
		if(trace_enable) {
			trace->dump(Verilated::time());
			Verilated::timeInc(1);
		}
#endif
		
		// prepare for next rising edge
		module->clock = 0;
		module->eval();
		
#if TRACE_ENABLE
		// show clock oscillation in the trace graph
		if(trace_enable) {
			trace->dump(Verilated::time());
			Verilated::timeInc(1);
		}
#endif
		
		cycles++;
	} while((!module->io_out_last || outBufIdx)
		&& !TIMEOUT);
	
	module->final();

#if !YUQING_MODE
	if(inf != stdin)
		fclose(inf);
	if(outf != stdout)
		fclose(outf);
#endif
	
#if TRACE_ENABLE
		if(trace_enable) {
			trace->close();
		}
#endif
	
	delete module;
	
#if !YUQING_MODE
	if(!TIMEOUT)
		fprintf(stderr, "cycles: %d\n", cycles);
	else
		fprintf(stderr, "cycles: %d (timeout)\n", cycles);
	
	return 0;
#else
	if(!TIMEOUT)
		return cycles;
	else
		return -1;
#endif
}
