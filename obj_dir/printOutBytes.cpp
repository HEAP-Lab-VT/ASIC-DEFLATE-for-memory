#include <sys/stat.h>
#include <fstream>
#include <iostream>
#define PAGE_SIZE 4096 // number of bytes in a page

int main(int argc, char **argv, char **env)
{
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

	for (size_t loopIndex = 0; loopIndex < fileBytes/PAGE_SIZE; loopIndex++)
	{
		if (fileBytes > PAGE_SIZE)
		{
			myFile.read(inputArray, PAGE_SIZE);
		}
		
		for (int fileIndex = 0; fileIndex < PAGE_SIZE; fileIndex++){
			printf("decompressedDataIn[%u] = 8'h%x;\n",  fileIndex, inputArray[fileIndex]);
		}

	}
	printf(argv[0]);
	printf("\n");
	exit(0);
}
