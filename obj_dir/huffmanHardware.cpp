#include "VhuffmanCompressorDecompressorWrapper.h"
#include "verilated.h"
#include <sys/stat.h>
#include <fstream>
#include <iostream>
#define PAGE_SIZE 4096 // number of bytes in a page

int huffmanHardware(char *inputArray, char *outputArray, int argc, char **argv)
{
	Verilated::commandArgs(argc, argv);
	VhuffmanCompressorDecompressorWrapper *top = new VhuffmanCompressorDecompressorWrapper;
	top->start = 0;
	top->reset = 0;
	top->clock = 0;
	top->compressionLimit = PAGE_SIZE;
	for (size_t index = 0; index < PAGE_SIZE; index++)
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
	long long counter = 0;
	while (!(top->finished && !lastFinished) || counter > 500000)
	{
		counter++;
		lastFinished = top->finished;
		top->eval();
		top->clock = 0;
		top->eval();
		top->clock = 1;
	}
	bool equal = true;

	for (size_t index = 0; index < PAGE_SIZE; index++)
	{
		outputArray[index] = (char)top->dataOut[index];
	}
	for (size_t index = 0; index < PAGE_SIZE; index++)
	{
		if (outputArray[index] != inputArray[index])
		{
			equal = false;
		}
	}

	if (equal)
	{
		return top->outputBytes;
	}
	else
	{
		return -1;
	}

	delete top;
}
