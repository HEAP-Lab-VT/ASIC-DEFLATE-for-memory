module singleCyclePatternSearchWrapper(
  input clock,
  input reset,
  output writeDataReady,
  input writeDataValid,
  input [7:0] writeData,
  output patternDataReady,
  input patternDataValid,
  input [7:0] patternData [0:6],
  input [2:0] patternDataLength,
  input matchResultReady,
  output matchResultValid,
  output [11:0] matchResultIndex,
  output [2:0] matchResultLength
  );

  singleCyclePatternSearch scps(
  .clock(clock),
  .reset(reset),
  .io_writeData_ready(writeDataReady),
  .io_writeData_valid(writeDataValid),
  .io_writeData_bits(writeData),
  .io_patternData_ready(patternDataReady),
  .io_patternData_valid(patternDataValid),
  .io_patternData_bits_pattern_0(patternData[0]),
  .io_patternData_bits_pattern_1(patternData[1]),
  .io_patternData_bits_pattern_2(patternData[2]),
  .io_patternData_bits_pattern_3(patternData[3]),
  .io_patternData_bits_pattern_4(patternData[4]),
  .io_patternData_bits_pattern_5(patternData[5]),
  .io_patternData_bits_pattern_6(patternData[6]),
  .io_patternData_bits_length(patternDataLength),
  .io_matchResult_ready(matchResultReady),
  .io_matchResult_valid(matchResultValid),
  .io_matchResult_bits_patternIndex(matchResultIndex),
  .io_matchResult_bits_length(matchResultLength)
  );

endmodule