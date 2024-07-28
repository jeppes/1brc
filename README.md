~5s solution to the [1 billion row challenge](https://github.com/gunnarmorling/1brc).

---

Here is a summary of everything I tried and the resulting execution time after each step.

1. No optimizations: `196648ms`
2. Chunking the file and running one coroutine per chunk: `77565ms`
3. Tweaking parameters (buffer size, chunk count): `52876ms`
4. Use one hashmap per coroutine instead of one shared concurrent hashmap to avoid coordination overhead of concurrent writes: `48889ms`
5. Use ints instead of doubles: `35586ms`
6. Convert byte and string sequences to a single string sequence: `25815ms`
7. Use a fix sized buffer for each line: `21220ms`
8. Look for ';' when parsing bytes instead of creating a string then splitting on ';':
   `15032ms`
9. Don't clear the line buffer, just keep an index of the latest written byte: `14030ms`
10. Build a custom int parser instead of decoding numbers as strings and using the built-in parser: `8714ms`
11. Use a memory-mapped file instead of a random-access file (initial regression): `14287ms`
12. Tuning parameters again `11490ms`
13. Buffered reading of memory-mapped file instead of one byte at a time: `7675ms`
14. Use a rolling hash for city names instead of decoding all names as strings: `4572ms`

Best execution time: `4572ms`
