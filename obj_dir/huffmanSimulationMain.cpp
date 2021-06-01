#include "huffmanHardware.cpp"

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
	char outputArray[PAGE_SIZE];
	std::ifstream myFile(fileName, std::ifstream::in | std::ifstream::binary);

	for (size_t loopIndex = 0; loopIndex < fileBytes/PAGE_SIZE; loopIndex++)
	{
		if (fileBytes >= PAGE_SIZE)
		{
			myFile.read(inputArray, PAGE_SIZE);
		}
		else{
			printf("Error, file cannot be read fully\n");
            		return -1;
		}
		size_t huffmanCompressedSize = huffmanHardware(inputArray, outputArray, argc, argv);
        	printf("The bytes were compressed down to %d\n", huffmanCompressedSize);
		for(size_t checkIndex = 0; checkIndex < PAGE_SIZE; checkIndex++){
			if(inputArray[checkIndex] != outputArray[checkIndex]){
				printf("Index %d: %d != %d\n", checkIndex, inputArray[checkIndex], outputArray[checkIndex]);
			}
		}
	}
	printf(argv[0]);
	printf("\n");
	exit(0);
}
