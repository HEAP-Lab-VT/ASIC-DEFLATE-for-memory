#include "VsingleCyclePatternSearchWrapper.h"
#include "verilated.h"
#include <sys/stat.h>
#include <fstream>
#include <iostream>
#include <cstdlib>
#include <iostream>
#include <ctime>
#define PAGE_SIZE 4096 // number of bytes in a page
// This is the number of cycles before the hardware times out.
#define TIMEOUT_CYCLES 10000
// This is the number of bytes in the maximum case length of the hardware.
#define MAX_PATTERN_BYTES 7

struct matchResult
{
	// A match length of 0 means there was no match.
	size_t length;
	size_t index;
};

// This function returns a match result from checking a given address of the input array against the match pattern.
struct matchResult checkMatch(char *inputArray, size_t inputArrayOffset, char *matchAddress, size_t length)
{
	for (size_t index = 0; index < length; index++)
	{
		if (inputArray[inputArrayOffset + index] != matchAddress[index])
		{
			return (struct matchResult){index, inputArrayOffset};
		}
	}
	return (struct matchResult){length, inputArrayOffset};
}

// This function returns the index of the first longest match in the data.
struct matchResult softwareMatchSearch(char *inputArray, char *matchAddress, size_t length)
{
	struct matchResult firstLongestMatch;
	firstLongestMatch.length = 0;
	for (size_t index = 0; index < PAGE_SIZE; index++)
	{
		struct matchResult newMatch;
		// This prevents us from going out of the bounds of the array.
		if (index + length < PAGE_SIZE)
		{
			newMatch = checkMatch(inputArray, index, matchAddress, length);
		}
		else
		{
			newMatch = checkMatch(inputArray, index, matchAddress, PAGE_SIZE - index);
		}
		if (newMatch.length > firstLongestMatch.length)
		{
			firstLongestMatch = newMatch;
		}
	}
	// Converting from the to get the core
	return firstLongestMatch;
}

// This function returns the index of the first longest match in the data using the hardware simulation.
struct matchResult hardwareMatchSearch(char *inputArray, char *matchAddress, size_t length)
{
	struct matchResult firstLongestMatch = {0, 0};
	VsingleCyclePatternSearchWrapper *top = new VsingleCyclePatternSearchWrapper;

	// This block of statements sets up the hardware and resets everything with a few clock cycles.
	top->writeDataValid = 0;
	top->matchResultReady = 0;
	top->patternDataValid = 0;
	top->patternDataLength = 0;
	top->patternData[0] = 0;
	top->patternData[1] = 0;
	top->patternData[2] = 0;
	top->patternData[3] = 0;
	top->patternData[4] = 0;
	top->patternData[5] = 0;
	top->patternData[6] = 0;
	top->writeData = 0;
	top->reset = 0;
	top->clock = 0;
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
	top->reset = 0;
	top->eval();
	top->clock = 1;
	top->eval();
	top->clock = 0;
	top->eval();

	// This loads the data into the hardware or exits if the hardware takes too long.
	size_t loopIndex = 0;
	size_t loopExitCount = 0;
	while (loopIndex < PAGE_SIZE)
	{
		if (top->writeDataReady)
		{
			top->writeDataValid = 1;
			top->writeData = inputArray[loopIndex];
			loopIndex++;
		}
		else
		{
			top->writeDataValid = 0;
		}
		top->clock = 0;
		top->eval();
		top->clock = 1;
		top->eval();
		if (loopExitCount > TIMEOUT_CYCLES)
		{
			printf("Hardware timeout reached, exitting\n");
			exit(1);
		}
		loopExitCount++;
	}
	top->writeDataValid = 0;

	top->clock = 0;
	top->eval();
	top->clock = 1;
	top->eval();
	top->clock = 0;
	top->eval();

	// Once the data is loaded in, the pattern can be given.
	top->matchResultReady = 1;
	top->patternDataValid = 1;
	top->patternDataLength = length;
	for (size_t index = 0; index < MAX_PATTERN_BYTES; index++)
	{
		if (index < length)
		{
			// Load in some data to the pattern
			top->patternData[index] = matchAddress[index];
		}
		else
		{
			// We want the hardware pattern data to be defined even if it is invalid.
			top->patternData[index] = 0;
		}
	}
	top->clock = 0;
	top->eval();
	top->clock = 1;
	top->eval();
	top->clock = 0;
	top->eval();

	firstLongestMatch.index = top->matchResultIndex;
	firstLongestMatch.length = top->matchResultLength;

	return firstLongestMatch;
}

int main(int argc, char **argv, char **env)
{
	// This initializes random number generation to enable randomly selecting patterns to test the hardware pattern selection.
	std::srand(std::time(nullptr));

	struct stat fileStats;
	unsigned int fileBytes;
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

	for (size_t loopIndex = 0; loopIndex < fileBytes / PAGE_SIZE; loopIndex++)
	{
		if (fileBytes >= PAGE_SIZE)
		{
			myFile.read(inputArray, PAGE_SIZE);
		}
		else
		{
			printf("Error, file cannot be read fully\n");
			return -1;
		}
		// This randomly chooses a pattern length and an index for the pattern start in the array
		size_t patternLength = (std::rand() % (MAX_PATTERN_BYTES)) + 1;
		size_t patternStartingIndex = std::rand() % PAGE_SIZE;
		struct matchResult softwareMatch = softwareMatchSearch(inputArray, &(inputArray[patternStartingIndex]), patternLength);
		struct matchResult hardwareMatch = hardwareMatchSearch(inputArray, &(inputArray[patternStartingIndex]), patternLength);
		printf("The %d characters starting at the %dth index were checked, and the longest hardware match was %d bytes at index %d. The longest software match was %d bytes at index %d.\n", patternLength, patternStartingIndex, hardwareMatch.length, hardwareMatch.index, softwareMatch.length, softwareMatch.index);
		if(softwareMatch.length != hardwareMatch.length || softwareMatch.index != hardwareMatch.index){
			printf("The hardware and software pattern search results are not equal!\n");
			printf("The original pattern is ");
			for (size_t index = 0; index < patternLength; index++){
				printf("0x%X ", inputArray[index + patternStartingIndex]);
			}
			printf("\nThe check failed on page number %d\n", loopIndex);
			exit(-1);
		}
	}
	printf(argv[0]);
	printf("\n");
	exit(0);
}
