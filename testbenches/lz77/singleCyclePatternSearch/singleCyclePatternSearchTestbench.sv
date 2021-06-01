`timescale 1ns/1ps
module singleCyclePatternSearchTestbench();

reg clock, reset;
reg [7:0] writeData;
reg [7:0] patternData [0:6];
reg [2:0] patternDataLength;
reg matchResultReady;
reg writeDataValid, patternDataValid;
wire writeDataReady, patternDataReady, matchResultValid;
wire [11:0] matchResultIndex;
wire [2:0] matchResultLength;

singleCyclePatternSearchWrapper scps(
  .clock(clock),
  .reset(reset),
  .writeDataReady(writeDataReady),
  .writeDataValid(writeDataValid),
  .writeData(writeData),
  .patternDataReady(patternDataReady),
  .patternDataValid(patternDataValid),
  .patternData(patternData),
  .patternDataLength(patternDataLength),
  .matchResultReady(matchResultReady),
  .matchResultValid(matchResultValid),
  .matchResultIndex(matchResultIndex),
  .matchResultLength(matchResultLength)
);

reg [7:0] dataIn [0:4095];
wire [7:0] decompressedDataOut [0:4095];
integer readFileResult, inputFile, fileIterator;
// This is used to determine what 4KB page of data from the testbench input file to read
integer loopCount;
integer desiredLoop = 13;
// This is used to determine if we should break out of the loop.
integer loopExitCount;

integer count;
integer clockCount;


initial begin
	$dumpfile("traceOutput.fst");
	$dumpvars(0,singleCyclePatternSearchTestbench);
end

initial begin
  $display("starting signals");
  // These signals setup the reset signals in the beginning.
  patternDataValid = 0;
  writeDataValid = 0;
  matchResultReady = 0;
  clock = 0;
  reset = 0;
  #10;
  reset = 1;
  clock = 1;
  #10;
  clock = 0;
  #10;
  clock = 1;
  #10;
  clock = 0;
  #10;
  reset = 0;

  // This read the data from the input file.
  $display("opened testbench input file");
  inputFile = $fopen("testbenchInputFile.txt", "rb");
  for(loopCount = 0; loopCount <= desiredLoop; loopCount = loopCount + 1) begin
    readFileResult = $fread(dataIn, inputFile);
  end
  $fclose(inputFile);
  $display("closed testbench input file");

  // Once the data is read in from the input file, this loads it into the pattern search.
  $display("started writing the input data to the single cycle pattern search");
  loopCount = 0; 
  loopExitCount = 0;
  while(loopCount <= 4096 && loopExitCount < 10000) begin
    if(writeDataReady) begin
      writeDataValid = 1'b1;
      writeData = dataIn[loopCount];
      loopCount = loopCount + 1; 
      //$display("write data ready true");
    end else begin
      writeDataValid = 1'b0;
      $display("write data ready false loop exit count %0d", loopExitCount);
    end
    #10;
    clock = 0;
    #10;
    clock = 1;
    loopExitCount = loopExitCount + 1;
  end
  writeDataValid = 0;
  $display("finished writing the input data to the single cycle pattern search");

  #10;
  clock = 0;
  #10;
  clock = 1;
  #10;
  clock = 0;

  // Once the data is loaded in, the pattern can be given.
  matchResultReady = 1;
  patternData[0] = 8'h45;
  patternData[1] = 8'hFC;
  patternData[2] = 8'h0;
  patternData[3] = 8'h0;
  patternData[4] = 8'h0;
  patternData[5] = 8'h0;
  patternData[6] = 8'h0;
  patternDataValid = 1'b1;
  patternDataLength = 2;
  #10;
  clock = 1;
  #10;
  clock = 0;
  patternDataValid = 1'b0;

  #10;
  clock = 1;
  #10;
  clock = 0;
  $finish();
end

endmodule
