#include "VlzwAndHuffmanWrapper.h"
#include "verilated.h"
#include "verilated_vcd_c.h"
#include <sys/stat.h>
#include <fstream>
#include <iostream>
#define OUTPUT_TRACE 0
#define PAGE_SIZE 4096 // number of bytes in a page
#define DEBUG_STATISTICS true
#define MAX_CHARACTER_DEPTH 15
#define TIMEOUT_ENABLE true
#define TIMEOUT_CYCLES 30000

int main(int argc, char **argv, char **env)
{
#if OUTPUT_TRACE
	Verilated::commandArgs(argc, argv);
	Verilated::traceEverOn(true);
	VerilatedVcdC *trace = new VerilatedVcdC;
#endif
	VlzwAndHuffmanWrapper *top = new VlzwAndHuffmanWrapper;
#if OUTPUT_TRACE
	top->trace(trace, 99);
	trace->open("./outputTrace.vcd");
#endif
	struct stat fileStats;
	size_t fileBytes;
	char *fileName = argv[argc - 1];

	if (stat(fileName, &fileStats) == 0)
	{
		fileBytes = fileStats.st_size;
		printf("File is %d bytes\n", fileBytes);
	}
	else
	{
		printf("Failed to get file statistics, exitting now\n");
		return 1;
	}

	char inputArray[PAGE_SIZE];
	std::ifstream myFile(fileName, std::ifstream::in | std::ifstream::binary);

	for (int loopIndex = 0; loopIndex < fileBytes / PAGE_SIZE; loopIndex++)
	{
		if (fileBytes >= PAGE_SIZE)
		{
			myFile.read(inputArray, PAGE_SIZE);
		}
		// printf("The input in question is:");
		// for(int index = 0; index < PAGE_SIZE; index++){
		// 	printf("[%d]:%d,", index, inputArray[index]);
		// }
		// printf("\n\n\n");

		top->start = 0;
		top->reset = 0;
		top->clock = 0;
		for (int index = 0; index < PAGE_SIZE; index++)
		{
			top->dataIn[index] = inputArray[index];
		}
		top->eval();
		top->reset = 1;
		top->eval();
		top->clock = 1;
		top->eval();
		top->clock = 0;
		top->eval();
		top->clock = 1;
		top->eval();
		top->clock = 0;
		top->eval();
		top->reset = 0;
		top->clock = 1;
		top->eval();
		top->clock = 0;
		top->eval();

		top->start = 1;

		bool lastFinished = true;
		int counter = 0;
		while (!(top->finished && !lastFinished) || counter > 500000)
		{
			counter++;
			lastFinished = top->finished;
			top->eval();
#if OUTPUT_TRACE
			trace->dump(counter);
#endif
			top->clock = 0;
			top->eval();
#if OUTPUT_TRACE
			trace->dump(counter);
#endif
			top->clock = 1;
#if TIMEOUT_ENABLE
			if (counter > TIMEOUT_CYCLES) {
				printf("Timeout error, %d clock cycles exceeded\n", counter);
				return -1;
			}
#endif
		}
		// Adding a few extra clock cycles to be sure everything worked
		for (int index = 0; index < 10; index++)
		{
			counter++;
			top->eval();
#if OUTPUT_TRACE
			trace->dump(counter);
#endif
			top->clock = 0;
			top->eval();
#if OUTPUT_TRACE
			trace->dump(counter);
#endif
			top->clock = 1;
		}
		//printf("%d\n", counter);
		bool equal = true;
		for (int index = 0; index < PAGE_SIZE; index++)
		{
			if (((char)top->dataOut[index]) != ((char)inputArray[index]))
			{
				printf("index %d %d!=%d", index, (char)top->dataOut[index], (char)inputArray[index]);
				equal = false;
			}
		}
		if (equal)
		{
			printf("%u:i=o;%u,\n", loopIndex, top->outputBytes);
#if DEBUG_STATISTICS
			for (int index = 0; index <= MAX_CHARACTER_DEPTH; index++)
			{
				printf("characterDepth%d=%d\n", index, top->huffmanCharacterDepths[index]);
			}
			printf("escapeCharacterLength=%d\n", top->escapeCharacterLength);
			printf("huffmanTreeCharactersUsed=%d\n", top->huffmanTreeCharactersUsed);
			printf("dictionaryEntries=%d\n", top->dictionaryEntries);
			printf("longestSequenceLength=%d\n", top->longestSequenceLength);
			printf("lzwUncompressedBytes=%d\n", top->lzwUncompressedBytes);
			printf("lzwCompressedBytes=%d\n", top->lzwCompressedBytes);
			printf("huffmanCompressedBytes=%d\n", top->huffmanCompressedBytes);
			printf("huffmanCompressedBytes=%d\n", top->huffmanCompressedBytes);
       		printf("sequenceLengths_0=%d\n", top->sequenceLengths_0);
       		printf("sequenceLengths_1=%d\n", top->sequenceLengths_1);
       		printf("sequenceLengths_2=%d\n", top->sequenceLengths_2);
       		printf("sequenceLengths_3=%d\n", top->sequenceLengths_3);
       		printf("sequenceLengths_4=%d\n", top->sequenceLengths_4);
       		printf("sequenceLengths_5=%d\n", top->sequenceLengths_5);
       		printf("sequenceLengths_6=%d\n", top->sequenceLengths_6);
       		printf("sequenceLengths_7=%d\n", top->sequenceLengths_7);
       		printf("sequenceLengths_8=%d\n", top->sequenceLengths_8);
       		printf("sequenceLengths_9=%d\n", top->sequenceLengths_9);
       		printf("sequenceLengths_10=%d\n", top->sequenceLengths_10);
       		printf("sequenceLengths_11=%d\n", top->sequenceLengths_11);
       		printf("sequenceLengths_12=%d\n", top->sequenceLengths_12);
       		printf("sequenceLengths_13=%d\n", top->sequenceLengths_13);
       		printf("sequenceLengths_14=%d\n", top->sequenceLengths_14);
       		printf("sequenceLengths_15=%d\n", top->sequenceLengths_15);
#endif
			std::cout << std::flush;
		}
		else
		{
			printf("loop %d of file %s: ", loopIndex, fileName);
			printf("input does not equal output\n");
			// printf("The input in question is:");
			// for(int index = 0; index < PAGE_SIZE; index++){
			// 	printf("[%d]:%d,", index, inputArray[index]);
			// }
			// printf("\n\n\n");
		}
	}
	printf(argv[0]);
	printf("\n");
	delete top;
#if OUTPUT_TRACE
	delete trace;
#endif
	exit(0);
}
