import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.*
import kotlin.io.path.toPath
import kotlin.math.max
import kotlin.math.min
import kotlin.system.measureTimeMillis

object Main

data class City(
    val name: String,
    var min: Int,
    var max: Int,
    var sum: Int,
    var count: Int,
)

fun normalize(n: Int): Double {
    return n / 10.0
}

fun format(n: Double): String {
    val formatted = "%.1f".format(Locale.US, n)
    return if (formatted == "-0.0") "0.0"
    else formatted
}


// I generated files of different sizes to simplify testing
enum class TestFile(val path: String) {
    TEST_1B("/measurements.txt"),
    TEST_100("/100.txt"),
    TEST_1K("/1k.txt"),
    TEST_10K("/10k.txt"),
    TEST_100K("/100k.txt"),
    TEST_1M("/1m.txt"),
    TEST_10M("/10m.txt"),
    TEST_100M("/100m.txt"),
}

/**
 * MappedByteBuffers are indexed by ints, which are not large enough
 * to fit the full 1b line file. So we split the file into multiple buffers
 * that are <= Int.MAX_VALUE in size.
 *
 * This class handles the details of reading from these buffers.
 */
class MemoryMappedFile(
    private val mappedByteBuffers: Array<MappedByteBuffer>
) {
    fun get(i: Long): Byte {
        val bufferIndex = i / Int.MAX_VALUE
        val mappedByteBuffer = mappedByteBuffers[bufferIndex.toInt()]
        val iInt = (i % Int.MAX_VALUE).toInt()
        return mappedByteBuffer.get(iInt)
    }

    fun copy(
        startOffset: Long,
        length: Int,
        destination: ByteArray
    ) {
        require(destination.size >= length) {
            "Destination array must be at least as long as the requested length"
        }
        val startBufferIndex = (startOffset / Int.MAX_VALUE).toInt()
        val startBuffer = mappedByteBuffers[startBufferIndex]
        val endBufferIndex = ((startOffset + length) / Int.MAX_VALUE).toInt()
        require(endBufferIndex - startBufferIndex <= 1) {
            "Copying from at most 1 adjacent buffer is supported!"
        }

        val firstOffset = (startOffset % Int.MAX_VALUE).toInt()
        val firstSize = min(length, startBuffer.limit() - firstOffset)

        startBuffer.get(
            firstOffset,
            destination,
            0,
            firstSize
        )

        if (firstSize < length) {
            val secondSize = length - firstSize
            mappedByteBuffers.getOrNull(startBufferIndex + 1)?.get(
                0,
                destination,
                firstSize,
                secondSize
            )
        }
    }

    fun limit(): Long {
        return mappedByteBuffers.sumOf { it.limit().toLong() }
    }
}

fun <T> withMemoryMappedFile(
    testFile: TestFile,
    body: (MemoryMappedFile) -> T
): T {
    val filePath = Main::class.java.getResource(testFile.path)!!.toURI().toPath()
    return Files.newByteChannel(
        filePath, StandardOpenOption.READ
    ).use { fileChannel ->
        val mappedByteBuffers = mutableListOf<MappedByteBuffer>()
        var offset = 0L
        while (offset < fileChannel.size()) {
            val size = min(fileChannel.size() - offset, Int.MAX_VALUE.toLong())
            val mappedByteBuffer = (fileChannel as FileChannel).map(
                FileChannel.MapMode.READ_ONLY,
                offset,
                size
            )
            mappedByteBuffers.add(mappedByteBuffer)
            offset += size
        }

        body(MemoryMappedFile(mappedByteBuffers.toTypedArray()))
    }
}

const val newLineByte = '\n'.code.toByte()
const val semicolonByte = ';'.code.toByte()
const val minusByte = '-'.code.toByte()
const val periodByte = '.'.code.toByte()

data class Chunk(
    val startOffset: Long,
    val size: Long
)

/**
 * The goal here is to parse a file into `chunkCount` sections.
 * We need each chunk to start and end on a newline, so we first
 * determine the rough size of each section, then run through the file in
 * increments of that size and find the closest newline to each expected starting point.
 */
fun chunks(file: MemoryMappedFile, chunkCount: Int): List<Chunk> {
    val fileLength = file.limit()
    val approximateChunkSize = fileLength / chunkCount

    val chunks = mutableListOf<Chunk>()
    var startingPoint = 0L
    for (i in 0..<chunkCount) {
        val previousStartingPoint = startingPoint
        startingPoint += approximateChunkSize
        // Find nearest newline
        while (
            startingPoint < fileLength &&
            file.get(startingPoint) != newLineByte
        ) {
            startingPoint++
        }
        startingPoint++


        chunks += Chunk(
            startOffset = previousStartingPoint,
            size = min(
                startingPoint - previousStartingPoint,
                fileLength - previousStartingPoint
            )
        )
    }

    return chunks
}

class Cities(
    private val cities: MutableMap<Long, City> = HashMap<Long, City>(500),
) {
    fun get(
        hash: Long,
        byteArray: ByteArray,
        byteArrayIndex: Int
    ): City {
        val city = cities[hash]
        if (city != null) {
            return city
        } else {
            val name = byteArray.decodeToString(0, byteArrayIndex)
            val default = City(
                name = name,
                min = Int.MAX_VALUE,
                max = Int.MIN_VALUE,
                sum = 0,
                count = 0
            )
            cities[hash] = default
            return default
        }
    }

    fun asMap(): Map<String, City> {
        return cities.values.associateBy { it.name }
    }
}

fun solveChunk(
    file: MemoryMappedFile,
    chunk: Chunk,
): Map<String, City> {
    val cities = Cities()

    // The inner buffer is used to store the bytes of the current city or temperature
    // (depending on which is being parsed at the moment)
    val innerBuffer = ByteArray(
        // Assumption: lines are not longer than this
        50
    )
    var innerBufferIndex = 0

    // After we have successfully parsed a city on a line, this variable
    // will hold the city object which can be mutated once we have parsed the temperature
    var currentCity: City? = null

    // This is a chunk of the file that is being read. By doing this we can avoid
    // excessive amounts of IO operations which may be expensive.
    val fileBuffer = ByteArray(1024 * 512)
    var fileBufferIndex = 0
    file.copy(chunk.startOffset, fileBuffer.size, fileBuffer)

    // A rolling hash of the bytes that make up a city name.
    // This is used to avoid needing to decode city names into a string
    // on each line.
    val rollingHashStart = 7L
    val rollingHashPrime = 31L
    var cityRollingHash = rollingHashStart

    for (i in chunk.startOffset..<chunk.startOffset + chunk.size) {
        // Update the file buffer if we have reacted the end of it
        if (fileBufferIndex == fileBuffer.size) {
            file.copy(
                i,
                min(
                    fileBuffer.size,
                    (chunk.startOffset + chunk.size - i).toInt()
                ), fileBuffer
            )
            fileBufferIndex = 0
        }
        val byte = fileBuffer[fileBufferIndex]
        fileBufferIndex++

        when (byte) {
            semicolonByte -> {
                // Reached the end of the city part
                currentCity =
                    cities.get(hash = cityRollingHash, byteArray = innerBuffer, byteArrayIndex = innerBufferIndex)

                innerBufferIndex = 0
                cityRollingHash = rollingHashStart
            }

            newLineByte -> {
                // Reached the end of the temperature part
                val temperature = parseNumberFromBytes(innerBuffer, innerBufferIndex)

                require(currentCity != null)
                currentCity.min = min(temperature, currentCity.min)
                currentCity.max = max(temperature, currentCity.max)
                currentCity.sum += temperature
                currentCity.count += 1

                currentCity = null

                innerBufferIndex = 0
            }

            else -> {
                if (currentCity == null) {
                    cityRollingHash = rollingHashPrime * cityRollingHash + byte
                }
                innerBuffer[innerBufferIndex] = byte
                innerBufferIndex++
            }
        }
    }

    return cities.asMap()
}


/**
 * We exploit the following assumptions to parse numbers quickly:
 * - numbers have one decimal point
 * - numbers have one digit to the right of the decimal point
 * - numbers have at least one digit and at most two digits to the left of the decimal point
 * - numbers can be negative and in such case start with a '-'
 *
 * Numbers are parsed to integers for better performance and only converted to floating-point numbers when printing.
 */
fun parseNumberFromBytes(
    byteArray: ByteArray,
    toIndex: Int,
): Int {
    var number = 0
    for (index in toIndex - 1 downTo 0) {
        val byte = byteArray[index]
        when (index) {
            toIndex - 1 -> {
                number += byte - 48
            }

            toIndex - 2 -> {
                require(byte == periodByte)
            }

            toIndex - 3 -> {
                number += (byte - 48) * 10
            }

            toIndex - 4 -> {
                if (byte == minusByte) {
                    number *= -1
                } else {
                    number += (byte - 48) * 100
                }
            }

            toIndex - 5 -> {
                require(byte == minusByte)

                number *= -1

                continue
            }

            else ->
                require(false) { "The number was too large to parse" }
        }
    }

    return number
}


/**
 * Each coroutine creates its own map of cities to look at in order to avoid
 * needing to coordinate writes across coroutines. After each coroutine completes
 * its computation, this function can be used to merge their results.
 */
fun mergeCityMaps(maps: List<Map<String, City>>): Map<String, City> {
    val output = mutableMapOf<String, City>()
    for (map in maps) {
        for (entry in map.entries) {
            val city = output[entry.key]

            if (city != null) {
                city.min = min(entry.value.min, city.min)
                city.max = max(entry.value.max, city.max)
                city.sum += entry.value.sum
                city.count += entry.value.count
            } else {
                output[entry.key] = entry.value.copy()
            }
        }
    }

    return output
}

fun solve(chunkCount: Int, testFile: TestFile): String {
    return withMemoryMappedFile(testFile) { file ->
        runBlocking(Dispatchers.Default) {
            // Split the test file into chunks and solve each chunk on its own coroutine
            val cityMaps =
                chunks(file, chunkCount)
                    .map { chunk ->
                        async {
                            solveChunk(file = file, chunk = chunk)
                        }
                    }
                    .awaitAll()


            val cities = mergeCityMaps(cityMaps)

            val citiesPrinted = cities.entries.sortedBy { it.key }
                .joinToString(separator = ", ") {
                    val min = normalize(it.value.min)
                    val max = normalize(it.value.max)
                    val sum = normalize(it.value.sum)
                    val count = (it.value.count)
                    val cityName = it.key

                    val mean = sum / count

                    val printed = "${cityName}=${format(min)}/${format(mean)}/${format(max)}"
                    return@joinToString printed
                }

            "{${citiesPrinted}}"
        }
    }
}


fun main() {
    val chunkCount = Runtime.getRuntime().availableProcessors() * 10

    run {
        println("\n==================\n")
        val testFile = TestFile.TEST_10M
        println("Testing with 10m lines")

        val executionTime = measureTimeMillis {
            val solution = solve(chunkCount = chunkCount, testFile = testFile)
            val expected =
                "{Abha=-22.6/17.9/63.5, Abidjan=-10.8/26.0/64.2, Abéché=-11.4/29.4/69.0, Accra=-14.3/26.4/66.4, Addis Ababa=-21.6/16.0/58.1, Adelaide=-19.5/17.3/59.9, Aden=-17.9/29.1/66.5, Ahvaz=-17.5/25.4/66.9, Albuquerque=-24.5/13.9/52.7, Alexandra=-35.4/10.9/53.7, Alexandria=-19.7/20.0/56.7, Algiers=-22.1/18.1/55.7, Alice Springs=-20.2/21.0/65.4, Almaty=-35.9/10.1/48.4, Amsterdam=-29.5/10.1/46.6, Anadyr=-43.1/-6.8/34.1, Anchorage=-35.1/2.9/42.1, Andorra la Vella=-29.8/9.7/46.2, Ankara=-24.0/12.1/51.8, Antananarivo=-23.0/17.8/54.4, Antsiranana=-12.9/25.2/65.4, Arkhangelsk=-35.0/1.3/42.7, Ashgabat=-23.4/17.1/57.0, Asmara=-25.4/15.5/56.5, Assab=-10.9/30.4/67.1, Astana=-33.8/3.5/50.9, Athens=-19.2/19.3/58.4, Atlanta=-22.7/17.0/56.5, Auckland=-20.9/15.2/54.8, Austin=-18.7/20.7/60.7, Baghdad=-19.6/22.7/66.0, Baguio=-28.9/19.5/59.9, Baku=-29.1/15.1/61.8, Baltimore=-31.0/13.0/50.7, Bamako=-16.3/27.8/67.7, Bangkok=-12.6/28.7/67.8, Bangui=-14.3/26.0/70.8, Banjul=-10.3/26.0/68.3, Barcelona=-20.5/18.2/61.6, Bata=-16.6/25.1/62.8, Batumi=-26.7/13.9/54.4, Beijing=-31.1/12.9/54.4, Beirut=-17.5/21.0/62.7, Belgrade=-29.0/12.5/53.0, Belize City=-14.9/26.7/67.9, Benghazi=-21.5/19.8/59.2, Bergen=-31.3/7.7/46.0, Berlin=-33.0/10.4/52.8, Bilbao=-30.4/14.6/55.6, Birao=-12.0/26.4/64.3, Bishkek=-25.4/11.3/49.6, Bissau=-14.6/27.0/67.6, Blantyre=-24.4/22.3/61.3, Bloemfontein=-21.4/15.6/59.9, Boise=-27.7/11.4/49.2, Bordeaux=-25.5/14.2/56.2, Bosaso=-11.0/30.0/73.5, Boston=-26.1/10.8/46.4, Bouaké=-13.0/26.0/64.0, Bratislava=-28.6/10.5/52.4, Brazzaville=-14.8/25.0/63.9, Bridgetown=-12.2/26.9/87.0, Brisbane=-16.7/21.4/64.4, Brussels=-30.9/10.5/53.2, Bucharest=-27.9/10.8/53.8, Budapest=-29.8/11.4/52.6, Bujumbura=-15.7/23.7/68.1, Bulawayo=-21.7/18.8/63.4, Burnie=-29.8/13.1/54.6, Busan=-27.8/15.0/59.8, Cabo San Lucas=-18.4/23.9/66.0, Cairns=-10.9/25.0/62.9, Cairo=-16.9/21.4/65.4, Calgary=-34.5/4.4/45.4, Canberra=-24.5/13.1/49.9, Cape Town=-25.4/16.2/58.3, Changsha=-26.3/17.4/55.2, Charlotte=-24.1/16.0/58.0, Chiang Mai=-19.0/25.7/63.5, Chicago=-28.8/9.7/54.0, Chihuahua=-22.3/18.6/61.1, Chittagong=-14.2/25.8/68.9, Chișinău=-29.6/10.2/47.3, Chongqing=-22.3/18.7/58.8, Christchurch=-30.6/12.1/51.9, City of San Marino=-29.2/11.9/57.9, Colombo=-14.9/27.4/63.8, Columbus=-28.5/11.7/50.8, Conakry=-11.4/26.3/67.5, Copenhagen=-31.1/9.2/50.8, Cotonou=-12.7/27.2/73.1, Cracow=-34.2/9.2/48.3, Da Lat=-21.2/17.9/58.3, Da Nang=-13.2/25.9/67.4, Dakar=-20.0/24.0/68.5, Dallas=-21.1/19.0/64.6, Damascus=-24.3/16.9/57.3, Dampier=-16.5/26.3/63.9, Dar es Salaam=-11.8/25.8/66.0, Darwin=-10.7/27.6/70.1, Denpasar=-16.8/23.7/66.0, Denver=-25.8/10.5/49.2, Detroit=-27.9/10.0/53.3, Dhaka=-13.9/25.9/65.7, Dikson=-55.8/-11.0/35.0, Dili=-19.4/26.6/63.9, Djibouti=-9.1/29.9/71.1, Dodoma=-22.5/22.7/63.4, Dolisie=-23.8/24.0/62.7, Douala=-17.1/26.7/64.4, Dubai=-12.6/26.8/68.2, Dublin=-28.7/9.8/51.2, Dunedin=-28.5/11.1/53.5, Durban=-19.9/20.6/61.6, Dushanbe=-23.4/14.7/57.5, Edinburgh=-34.2/9.3/53.5, Edmonton=-32.7/4.1/46.6, El Paso=-19.1/18.0/58.0, Entebbe=-20.6/21.0/63.9, Erbil=-21.1/19.5/63.0, Erzurum=-35.4/5.0/46.0, Fairbanks=-43.5/-2.2/40.6, Fianarantsoa=-24.8/17.9/63.0, Flores,  Petén=-12.7/26.3/68.6, Frankfurt=-30.8/10.6/53.3, Fresno=-22.4/17.9/56.8, Fukuoka=-23.9/17.1/59.3, Gaborone=-19.8/21.1/66.0, Gabès=-18.5/19.5/58.6, Gagnoa=-19.0/26.1/72.2, Gangtok=-26.7/15.3/54.8, Garissa=-7.5/29.3/76.0, Garoua=-8.7/28.3/70.0, George Town=-12.4/28.0/64.6, Ghanzi=-16.7/21.4/63.1, Gjoa Haven=-56.0/-14.4/27.2, Guadalajara=-24.4/20.9/61.7, Guangzhou=-21.7/22.3/59.6, Guatemala City=-21.9/20.5/57.3, Halifax=-33.0/7.6/47.5, Hamburg=-27.9/9.7/50.5, Hamilton=-28.3/13.8/53.1, Hanga Roa=-19.8/20.5/64.1, Hanoi=-14.6/23.6/65.5, Harare=-24.4/18.4/58.5, Harbin=-40.3/5.0/47.9, Hargeisa=-18.2/21.6/58.3, Hat Yai=-16.2/27.0/69.4, Havana=-16.1/25.3/65.2, Helsinki=-33.1/5.9/46.7, Heraklion=-18.0/19.1/56.4, Hiroshima=-22.3/16.3/54.3, Ho Chi Minh City=-15.8/27.4/70.9, Hobart=-25.9/12.7/53.7, Hong Kong=-21.8/23.3/61.2, Honiara=-11.2/26.5/64.9, Honolulu=-15.7/25.5/63.3, Houston=-18.6/20.8/58.9, Ifrane=-27.3/11.4/48.8, Indianapolis=-30.5/11.7/52.1, Iqaluit=-47.2/-9.3/30.0, Irkutsk=-44.4/1.0/38.5, Istanbul=-27.0/13.9/54.1, Jacksonville=-19.1/20.2/60.5, Jakarta=-18.1/26.6/69.6, Jayapura=-22.7/27.0/65.5, Jerusalem=-25.5/18.3/66.8, Johannesburg=-22.5/15.6/57.0, Jos=-16.3/22.8/63.8, Juba=-10.0/27.8/67.1, Kabul=-26.8/12.2/50.7, Kampala=-19.4/20.0/64.6, Kandi=-10.3/27.8/65.9, Kankan=-12.2/26.5/61.0, Kano=-10.3/26.3/69.6, Kansas City=-27.1/12.6/52.3, Karachi=-18.6/26.0/65.6, Karonga=-20.5/24.4/66.2, Kathmandu=-23.4/18.3/57.2, Khartoum=-9.9/29.9/69.2, Kingston=-13.9/27.3/66.1, Kinshasa=-16.1/25.2/63.5, Kolkata=-12.8/26.8/69.0, Kuala Lumpur=-9.5/27.3/66.8, Kumasi=-13.6/26.1/74.0, Kunming=-28.6/15.6/56.8, Kuopio=-35.8/3.4/41.4, Kuwait City=-15.1/25.7/67.6, Kyiv=-30.2/8.4/51.0, Kyoto=-23.0/15.9/56.0, La Ceiba=-16.8/26.1/68.2, La Paz=-17.9/23.7/61.6, Lagos=-14.0/26.8/63.4, Lahore=-17.5/24.3/65.6, Lake Havasu City=-18.3/23.8/66.1, Lake Tekapo=-35.2/8.7/45.9, Las Palmas de Gran Canaria=-21.8/21.2/63.3, Las Vegas=-16.3/20.3/60.8, Launceston=-27.7/13.1/54.9, Lhasa=-32.5/7.6/50.4, Libreville=-11.6/25.8/67.1, Lisbon=-21.6/17.5/61.4, Livingstone=-15.9/21.8/61.1, Ljubljana=-30.4/11.0/50.7, Lodwar=-10.4/29.3/70.3, Lomé=-18.2/26.9/67.3, London=-30.5/11.3/53.0, Los Angeles=-24.4/18.5/60.4, Louisville=-28.5/13.9/62.1, Luanda=-12.5/25.8/67.5, Lubumbashi=-17.1/20.8/60.5, Lusaka=-21.3/19.9/59.6, Luxembourg City=-32.2/9.3/53.8, Lviv=-32.4/7.9/49.2, Lyon=-28.1/12.5/50.2, Madrid=-24.4/15.1/56.0, Mahajanga=-14.9/26.3/65.1, Makassar=-11.0/26.7/66.1, Makurdi=-13.4/26.1/73.1, Malabo=-16.1/26.4/68.6, Malé=-11.9/28.0/69.6, Managua=-15.3/27.4/77.4, Manama=-14.3/26.5/67.8, Mandalay=-12.8/28.1/69.7, Mango=-10.3/28.1/71.0, Manila=-17.4/28.4/69.2, Maputo=-23.3/22.8/60.9, Marrakesh=-22.2/19.7/57.4, Marseille=-23.9/15.7/57.6, Maun=-21.4/22.4/62.4, Medan=-19.6/26.6/68.2, Mek'ele=-16.8/22.6/59.9, Melbourne=-27.6/15.1/57.8, Memphis=-22.7/17.2/57.1, Mexicali=-21.9/23.1/64.0, Mexico City=-27.0/17.6/56.5, Miami=-12.9/25.0/63.9, Milan=-26.9/13.0/54.6, Milwaukee=-27.5/8.8/44.4, Minneapolis=-36.7/7.7/52.7, Minsk=-41.2/6.7/54.3, Mogadishu=-21.9/27.0/67.4, Mombasa=-13.3/26.2/72.4, Monaco=-26.6/16.4/53.2, Moncton=-34.5/6.0/52.5, Monterrey=-13.7/22.3/64.6, Montreal=-30.5/6.7/47.1, Moscow=-37.0/5.8/49.1, Mumbai=-16.1/27.3/67.2, Murmansk=-41.5/0.7/41.2, Muscat=-16.9/28.0/69.9, Mzuzu=-27.1/17.7/62.4, N'Djamena=-11.5/28.3/69.4, Naha=-20.7/23.0/66.4, Nairobi=-19.2/17.9/56.0, Nakhon Ratchasima=-13.5/27.3/66.3, Napier=-29.2/14.5/52.5, Napoli=-23.3/15.9/56.9, Nashville=-26.4/15.4/54.1, Nassau=-15.6/24.5/71.7, Ndola=-19.4/20.3/61.0, New Delhi=-16.3/25.0/59.4, New Orleans=-15.0/20.7/62.0, New York City=-26.8/12.9/49.2, Ngaoundéré=-16.4/21.9/63.6, Niamey=-10.7/29.4/68.7, Nicosia=-17.5/19.6/58.8, Niigata=-25.9/13.9/52.0, Nouadhibou=-15.8/21.3/59.3, Nouakchott=-20.4/25.7/70.1, Novosibirsk=-37.7/1.8/43.1, Nuuk=-37.2/-1.5/36.7, Odesa=-25.8/10.8/61.5, Odienné=-20.2/26.0/64.9, Oklahoma City=-24.1/15.9/56.2, Omaha=-33.9/10.6/47.1, Oranjestad=-10.5/28.2/68.3, Oslo=-36.2/5.8/46.8, Ottawa=-33.0/6.5/45.3, Ouagadougou=-12.3/28.4/69.4, Ouahigouya=-11.4/28.5/69.7, Ouarzazate=-21.3/18.9/60.1, Oulu=-43.7/2.7/42.7, Palembang=-15.6/27.5/67.4, Palermo=-28.7/18.5/55.9, Palm Springs=-18.1/24.5/64.2, Palmerston North=-24.4/13.2/50.6, Panama City=-10.3/28.0/70.1, Parakou=-14.8/26.6/67.4, Paris=-26.4/12.2/53.4, Perth=-21.9/18.7/55.1, Petropavlovsk-Kamchatsky=-40.5/1.9/40.3, Philadelphia=-34.1/13.2/52.4, Phnom Penh=-12.2/28.3/75.1, Phoenix=-15.4/23.8/59.1, Pittsburgh=-27.4/10.9/53.5, Podgorica=-26.6/15.3/62.1, Pointe-Noire=-11.0/26.2/67.9, Pontianak=-11.5/27.7/67.1, Port Moresby=-18.6/26.8/64.9, Port Sudan=-10.2/28.4/78.7, Port Vila=-17.1/24.3/64.5, Port-Gentil=-11.3/26.1/63.9, Portland (OR)=-28.3/12.4/57.5, Porto=-21.9/15.7/55.9, Prague=-31.4/8.5/51.0, Praia=-11.2/24.4/62.8, Pretoria=-21.6/18.2/56.3, Pyongyang=-29.5/10.7/54.1, Rabat=-25.7/17.1/57.3, Rangpur=-15.6/24.4/66.7, Reggane=-13.4/28.3/68.0, Reykjavík=-35.4/4.4/46.2, Riga=-31.5/6.2/49.4, Riyadh=-13.6/26.0/65.8, Rome=-24.4/15.3/54.7, Roseau=-13.6/26.1/65.0, Rostov-on-Don=-29.3/9.9/51.5, Sacramento=-28.2/16.3/59.0, Saint Petersburg=-37.8/5.7/43.8, Saint-Pierre=-33.0/5.6/43.1, Salt Lake City=-26.4/11.6/52.0, San Antonio=-23.0/20.9/58.7, San Diego=-25.1/17.9/56.8, San Francisco=-23.9/14.5/58.9, San Jose=-28.1/16.5/59.6, San José=-18.4/22.6/64.4, San Juan=-11.1/27.2/64.5, San Salvador=-18.1/23.0/63.2, Sana'a=-19.6/20.0/59.1, Santo Domingo=-9.7/25.9/67.7, Sapporo=-29.0/8.8/51.1, Sarajevo=-33.9/10.1/51.6, Saskatoon=-39.5/3.3/43.1, Seattle=-26.7/11.3/56.7, Seoul=-30.9/12.4/55.7, Seville=-24.5/19.2/59.1, Shanghai=-20.5/16.6/56.2, Singapore=-11.4/27.0/69.2, Skopje=-28.6/12.3/51.9, Sochi=-23.8/14.3/52.9, Sofia=-28.2/10.6/51.9, Sokoto=-15.8/27.9/66.7, Split=-21.8/16.0/59.4, St. John's=-33.0/5.0/40.8, St. Louis=-27.0/13.9/53.1, Stockholm=-38.4/6.6/47.5, Surabaya=-13.6/27.1/66.4, Suva=-11.4/25.7/66.5, Suwałki=-37.2/7.3/44.0, Sydney=-21.4/17.7/57.3, Ségou=-12.3/28.1/68.1, Tabora=-14.9/22.9/64.8, Tabriz=-28.4/12.6/49.4, Taipei=-13.4/23.0/63.2, Tallinn=-30.0/6.4/42.9, Tamale=-15.5/28.0/66.7, Tamanrasset=-16.9/21.8/64.5, Tampa=-15.8/22.9/66.8, Tashkent=-26.1/14.8/56.3, Tauranga=-22.9/14.8/54.0, Tbilisi=-29.5/12.8/53.5, Tegucigalpa=-16.1/21.7/60.9, Tehran=-20.4/17.0/53.4, Tel Aviv=-19.5/20.1/61.8, Thessaloniki=-24.0/16.0/55.0, Thiès=-15.9/24.1/66.7, Tijuana=-19.0/17.8/54.4, Timbuktu=-9.0/28.0/68.2, Tirana=-24.5/15.2/54.7, Toamasina=-19.0/23.4/61.7, Tokyo=-23.2/15.3/55.0, Toliara=-15.4/24.2/63.2, Toluca=-26.1/12.3/51.7, Toronto=-29.5/9.4/56.1, Tripoli=-21.0/20.1/62.5, Tromsø=-34.9/3.0/40.9, Tucson=-17.3/20.8/58.5, Tunis=-23.6/18.4/56.8, Ulaanbaatar=-41.7/-0.4/39.3, Upington=-22.7/20.4/57.3, Vaduz=-29.0/10.1/51.1, Valencia=-21.1/18.3/59.6, Valletta=-22.2/18.8/58.2, Vancouver=-27.1/10.4/48.2, Veracruz=-18.8/25.5/65.9, Vienna=-29.2/10.3/52.3, Vientiane=-15.3/25.9/65.1, Villahermosa=-11.7/27.1/67.5, Vilnius=-33.6/6.0/46.7, Virginia Beach=-23.5/15.8/54.4, Vladivostok=-33.9/4.9/43.4, Warsaw=-30.2/8.4/49.4, Washington, D.C.=-24.4/14.7/58.9, Wau=-20.8/27.9/66.5, Wellington=-25.1/13.0/55.9, Whitehorse=-37.8/-0.1/37.0, Wichita=-24.6/13.9/51.3, Willemstad=-10.8/28.0/65.8, Winnipeg=-37.9/3.0/44.8, Wrocław=-32.1/9.6/45.7, Xi'an=-29.0/14.1/57.6, Yakutsk=-57.8/-8.7/32.5, Yangon=-11.0/27.5/70.4, Yaoundé=-19.2/23.7/63.2, Yellowknife=-47.0/-4.3/35.0, Yerevan=-34.3/12.3/51.7, Yinchuan=-27.7/9.0/48.2, Zagreb=-24.0/10.9/54.5, Zanzibar City=-14.7/26.0/64.3, Zürich=-30.3/9.4/56.3, Ürümqi=-36.0/7.5/47.8, İzmir=-24.4/18.0/60.4}"
            if (solution != expected) {
                println("got     : $solution")
                println("expected: $expected")
                println("!! ${testFile.path} Solution was incorrect !!")
            } else {
                println("${testFile.path} Solution was correct")
            }
        }
        println("${testFile.path} took ${executionTime}ms")
    }

    run {
        println("\n==================\n")
        println("Full solution")

        println("Running with $chunkCount chunks")
        val executionTime = measureTimeMillis {
            val solution = solve(chunkCount = chunkCount, testFile = TestFile.TEST_1B)

            val expected =
                "{Abha=-34.7/18.0/63.8, Abidjan=-22.5/26.0/76.1, Abéché=-19.7/29.4/78.6, Accra=-24.4/26.4/75.1, Addis Ababa=-32.3/16.0/64.2, Adelaide=-31.1/17.3/70.7, Aden=-22.9/29.1/80.6, Ahvaz=-24.1/25.4/72.8, Albuquerque=-36.2/14.0/61.1, Alexandra=-40.8/11.0/60.8, Alexandria=-31.5/20.0/73.5, Algiers=-31.8/18.2/71.4, Alice Springs=-28.0/21.0/70.7, Almaty=-39.5/10.0/58.4, Amsterdam=-43.4/10.2/62.4, Anadyr=-56.2/-6.9/41.9, Anchorage=-48.7/2.8/55.8, Andorra la Vella=-37.6/9.8/58.6, Ankara=-39.2/12.0/67.6, Antananarivo=-29.2/17.9/70.0, Antsiranana=-25.9/25.2/74.8, Arkhangelsk=-47.5/1.3/52.6, Ashgabat=-32.1/17.1/65.4, Asmara=-36.3/15.6/67.8, Assab=-26.3/30.5/77.6, Astana=-46.2/3.5/57.6, Athens=-38.1/19.2/66.9, Atlanta=-35.5/17.0/65.5, Auckland=-32.7/15.2/66.7, Austin=-29.4/20.7/69.6, Baghdad=-23.5/22.8/72.4, Baguio=-28.9/19.5/68.9, Baku=-37.6/15.1/67.3, Baltimore=-36.1/13.1/66.4, Bamako=-23.2/27.8/81.6, Bangkok=-20.8/28.6/76.6, Bangui=-25.7/26.0/76.3, Banjul=-23.9/26.0/76.4, Barcelona=-32.0/18.2/69.6, Bata=-31.4/25.1/75.6, Batumi=-36.5/14.0/61.9, Beijing=-42.4/12.9/62.8, Beirut=-28.9/20.9/68.5, Belgrade=-39.6/12.5/66.0, Belize City=-22.6/26.7/77.4, Benghazi=-28.9/19.9/69.1, Bergen=-43.5/7.7/61.4, Berlin=-38.1/10.3/59.6, Bilbao=-41.6/14.7/63.2, Birao=-23.6/26.5/76.2, Bishkek=-39.3/11.3/60.9, Bissau=-20.3/27.0/71.7, Blantyre=-28.8/22.2/72.9, Bloemfontein=-37.0/15.6/62.5, Boise=-39.9/11.4/63.5, Bordeaux=-39.2/14.2/65.3, Bosaso=-19.1/30.0/84.4, Boston=-40.5/10.9/58.2, Bouaké=-28.4/26.0/75.5, Bratislava=-39.8/10.5/63.0, Brazzaville=-25.3/25.0/79.6, Bridgetown=-18.9/27.0/87.0, Brisbane=-26.4/21.4/69.7, Brussels=-41.3/10.5/63.3, Bucharest=-37.2/10.8/59.7, Budapest=-36.7/11.3/65.5, Bujumbura=-30.7/23.8/76.1, Bulawayo=-31.9/18.9/70.6, Burnie=-35.8/13.1/62.0, Busan=-34.8/15.0/65.1, Cabo San Lucas=-25.3/23.9/75.3, Cairns=-25.3/25.0/77.5, Cairo=-27.8/21.4/69.2, Calgary=-44.2/4.4/54.3, Canberra=-33.9/13.1/62.4, Cape Town=-33.6/16.2/67.3, Changsha=-33.2/17.4/69.1, Charlotte=-34.3/16.1/65.9, Chiang Mai=-26.9/25.8/72.6, Chicago=-39.9/9.8/59.6, Chihuahua=-29.9/18.6/66.7, Chittagong=-23.2/25.9/75.5, Chișinău=-38.3/10.2/62.4, Chongqing=-30.9/18.6/68.2, Christchurch=-39.7/12.2/60.7, City of San Marino=-36.1/11.8/64.5, Colombo=-21.9/27.4/76.3, Columbus=-39.9/11.7/62.4, Conakry=-23.4/26.4/75.3, Copenhagen=-39.2/9.1/60.1, Cotonou=-20.2/27.2/79.7, Cracow=-42.4/9.3/58.7, Da Lat=-30.2/17.9/69.6, Da Nang=-22.6/25.8/76.9, Dakar=-26.1/24.0/77.0, Dallas=-28.6/19.0/67.3, Damascus=-32.9/17.0/69.7, Dampier=-21.4/26.4/75.2, Dar es Salaam=-25.9/25.8/79.9, Darwin=-25.1/27.6/77.0, Denpasar=-28.4/23.7/73.8, Denver=-39.0/10.4/59.6, Detroit=-42.5/10.0/57.4, Dhaka=-24.4/25.9/76.3, Dikson=-61.3/-11.1/39.4, Dili=-31.9/26.6/77.1, Djibouti=-21.6/29.9/83.0, Dodoma=-32.8/22.7/70.4, Dolisie=-30.2/24.0/75.9, Douala=-24.4/26.7/75.2, Dubai=-28.4/26.9/75.1, Dublin=-39.8/9.8/65.3, Dunedin=-38.6/11.1/58.6, Durban=-31.7/20.6/71.2, Dushanbe=-37.1/14.7/67.9, Edinburgh=-36.9/9.3/60.4, Edmonton=-45.5/4.2/57.1, El Paso=-33.2/18.1/71.0, Entebbe=-28.6/21.0/70.7, Erbil=-31.9/19.5/70.2, Erzurum=-42.8/5.1/51.6, Fairbanks=-53.1/-2.3/48.3, Fianarantsoa=-32.1/17.9/65.7, Flores,  Petén=-23.3/26.4/75.8, Frankfurt=-38.4/10.6/59.5, Fresno=-30.7/17.9/67.7, Fukuoka=-31.9/17.0/64.5, Gaborone=-25.2/21.0/68.1, Gabès=-37.9/19.5/69.7, Gagnoa=-24.7/26.0/76.5, Gangtok=-33.8/15.2/61.9, Garissa=-18.6/29.3/86.2, Garoua=-22.9/28.3/76.6, George Town=-22.5/27.9/79.3, Ghanzi=-28.2/21.4/69.0, Gjoa Haven=-65.2/-14.4/37.6, Guadalajara=-26.3/20.9/71.5, Guangzhou=-25.3/22.4/71.6, Guatemala City=-28.6/20.4/74.2, Halifax=-40.9/7.5/60.5, Hamburg=-39.7/9.7/56.4, Hamilton=-35.1/13.8/70.5, Hanga Roa=-29.9/20.5/74.1, Hanoi=-26.6/23.6/72.6, Harare=-32.2/18.4/68.8, Harbin=-47.9/5.0/55.3, Hargeisa=-25.7/21.7/70.1, Hat Yai=-21.7/27.0/80.1, Havana=-29.2/25.2/77.2, Helsinki=-41.0/5.9/53.1, Heraklion=-35.6/18.9/67.5, Hiroshima=-31.1/16.3/66.1, Ho Chi Minh City=-20.7/27.4/77.0, Hobart=-37.6/12.7/64.9, Hong Kong=-25.5/23.3/73.9, Honiara=-22.0/26.5/79.6, Honolulu=-23.7/25.4/77.1, Houston=-27.9/20.8/69.5, Ifrane=-40.3/11.4/61.9, Indianapolis=-38.7/11.8/61.0, Iqaluit=-60.4/-9.3/36.6, Irkutsk=-48.2/1.0/48.9, Istanbul=-38.9/13.9/69.0, Jacksonville=-34.3/20.3/66.9, Jakarta=-23.6/26.7/76.9, Jayapura=-24.1/27.0/82.8, Jerusalem=-29.4/18.3/71.2, Johannesburg=-34.0/15.5/65.0, Jos=-27.2/22.8/74.9, Juba=-21.7/27.8/78.5, Kabul=-38.4/12.1/62.4, Kampala=-28.2/20.0/67.7, Kandi=-24.8/27.7/78.6, Kankan=-21.9/26.5/78.4, Kano=-24.0/26.4/80.4, Kansas City=-38.6/12.5/63.4, Karachi=-24.0/26.0/81.1, Karonga=-26.7/24.4/80.8, Kathmandu=-32.0/18.3/71.2, Khartoum=-19.2/29.9/78.8, Kingston=-20.0/27.4/77.8, Kinshasa=-32.9/25.3/76.0, Kolkata=-24.3/26.7/77.2, Kuala Lumpur=-21.2/27.3/81.5, Kumasi=-23.9/26.0/78.9, Kunming=-35.7/15.7/62.2, Kuopio=-45.1/3.4/52.6, Kuwait City=-28.9/25.7/76.3, Kyiv=-39.3/8.4/56.9, Kyoto=-31.2/15.8/64.2, La Ceiba=-30.8/26.2/72.8, La Paz=-26.3/23.7/76.2, Lagos=-21.9/26.8/76.5, Lahore=-24.6/24.3/79.3, Lake Havasu City=-29.6/23.7/73.3, Lake Tekapo=-42.2/8.7/56.4, Las Palmas de Gran Canaria=-24.9/21.2/70.5, Las Vegas=-32.0/20.3/71.2, Launceston=-43.2/13.1/60.9, Lhasa=-41.8/7.6/58.0, Libreville=-24.0/25.9/76.4, Lisbon=-32.7/17.5/73.7, Livingstone=-27.6/21.8/72.7, Ljubljana=-44.5/10.9/58.6, Lodwar=-18.3/29.3/81.1, Lomé=-22.3/26.9/77.7, London=-41.5/11.3/59.6, Los Angeles=-29.3/18.6/69.4, Louisville=-33.4/13.9/64.3, Luanda=-24.8/25.8/77.1, Lubumbashi=-27.0/20.8/69.5, Lusaka=-32.5/19.9/68.1, Luxembourg City=-41.2/9.3/58.7, Lviv=-42.6/7.8/60.5, Lyon=-37.0/12.5/61.5, Madrid=-35.1/15.0/65.0, Mahajanga=-25.5/26.3/79.9, Makassar=-19.6/26.7/79.1, Makurdi=-23.3/26.0/74.0, Malabo=-24.7/26.3/76.3, Malé=-21.8/28.0/74.5, Managua=-24.5/27.3/78.1, Manama=-26.8/26.5/72.9, Mandalay=-21.3/28.0/77.5, Mango=-18.5/28.1/77.7, Manila=-23.1/28.4/78.9, Maputo=-30.5/22.8/75.5, Marrakesh=-30.2/19.6/70.5, Marseille=-32.6/15.8/68.7, Maun=-34.6/22.4/69.3, Medan=-21.4/26.5/76.2, Mek'ele=-25.9/22.7/73.7, Melbourne=-33.7/15.1/65.9, Memphis=-34.3/17.2/68.5, Mexicali=-30.4/23.1/75.0, Mexico City=-32.2/17.5/67.4, Miami=-26.4/24.9/78.5, Milan=-37.6/13.0/62.9, Milwaukee=-45.2/8.9/58.8, Minneapolis=-42.9/7.8/57.8, Minsk=-42.3/6.7/56.7, Mogadishu=-25.1/27.1/76.1, Mombasa=-25.8/26.3/79.0, Monaco=-32.8/16.4/63.7, Moncton=-42.8/6.1/57.8, Monterrey=-27.9/22.3/74.1, Montreal=-42.1/6.8/58.3, Moscow=-42.8/5.8/58.7, Mumbai=-19.6/27.1/80.8, Murmansk=-49.9/0.6/47.9, Muscat=-19.6/28.0/83.6, Mzuzu=-36.5/17.7/69.4, N'Djamena=-22.4/28.3/83.7, Naha=-28.1/23.1/71.2, Nairobi=-30.5/17.8/65.2, Nakhon Ratchasima=-21.7/27.3/80.5, Napier=-34.9/14.6/62.2, Napoli=-35.4/15.9/62.5, Nashville=-32.1/15.4/68.3, Nassau=-24.5/24.6/76.7, Ndola=-31.6/20.3/72.8, New Delhi=-28.9/25.0/74.1, New Orleans=-29.3/20.7/70.3, New York City=-36.4/12.9/63.2, Ngaoundéré=-28.0/22.0/68.4, Niamey=-18.7/29.3/77.7, Nicosia=-28.5/19.7/73.2, Niigata=-39.0/13.9/63.7, Nouadhibou=-27.0/21.3/73.1, Nouakchott=-26.3/25.7/71.9, Novosibirsk=-46.9/1.7/50.6, Nuuk=-53.7/-1.4/54.4, Odesa=-40.7/10.7/61.8, Odienné=-32.0/26.0/73.8, Oklahoma City=-34.7/15.9/63.3, Omaha=-38.7/10.6/57.0, Oranjestad=-19.0/28.1/76.2, Oslo=-43.9/5.7/56.7, Ottawa=-42.6/6.6/54.8, Ouagadougou=-20.5/28.3/81.3, Ouahigouya=-19.1/28.6/80.7, Ouarzazate=-31.0/18.9/66.7, Oulu=-46.5/2.7/53.2, Palembang=-22.2/27.3/75.4, Palermo=-28.8/18.5/68.4, Palm Springs=-24.0/24.5/72.7, Palmerston North=-36.0/13.2/61.5, Panama City=-26.4/28.0/81.9, Parakou=-22.6/26.8/74.3, Paris=-38.9/12.3/60.5, Perth=-33.8/18.7/70.0, Petropavlovsk-Kamchatsky=-47.9/1.9/55.0, Philadelphia=-36.5/13.2/66.9, Phnom Penh=-22.5/28.3/81.2, Phoenix=-24.4/23.9/70.4, Pittsburgh=-40.5/10.8/58.1, Podgorica=-34.9/15.3/65.7, Pointe-Noire=-25.0/26.1/77.5, Pontianak=-22.4/27.7/78.4, Port Moresby=-26.1/26.9/76.8, Port Sudan=-21.8/28.4/78.7, Port Vila=-29.4/24.3/75.5, Port-Gentil=-20.2/26.0/72.9, Portland (OR)=-36.3/12.4/63.4, Porto=-33.3/15.7/66.1, Prague=-45.3/8.4/59.8, Praia=-23.7/24.4/75.1, Pretoria=-28.8/18.2/69.2, Pyongyang=-40.2/10.8/64.5, Rabat=-34.1/17.2/69.4, Rangpur=-25.0/24.4/78.0, Reggane=-25.8/28.3/78.2, Reykjavík=-44.5/4.3/56.5, Riga=-43.4/6.2/52.9, Riyadh=-21.5/26.0/77.2, Rome=-32.4/15.2/66.5, Roseau=-22.8/26.2/73.8, Rostov-on-Don=-40.7/9.9/61.2, Sacramento=-36.2/16.3/64.6, Saint Petersburg=-47.1/5.8/56.8, Saint-Pierre=-46.8/5.7/54.6, Salt Lake City=-42.0/11.6/64.0, San Antonio=-30.1/20.8/69.9, San Diego=-37.8/17.8/65.8, San Francisco=-32.6/14.6/68.3, San Jose=-35.4/16.4/64.5, San José=-25.4/22.6/80.0, San Juan=-23.5/27.2/75.8, San Salvador=-26.9/23.1/71.5, Sana'a=-31.3/20.0/67.6, Santo Domingo=-23.2/25.9/76.8, Sapporo=-44.2/8.9/62.4, Sarajevo=-41.3/10.1/57.6, Saskatoon=-47.5/3.3/51.8, Seattle=-48.3/11.3/67.7, Seoul=-36.7/12.5/59.4, Seville=-35.1/19.2/68.4, Shanghai=-30.7/16.7/68.0, Singapore=-21.1/27.0/73.6, Skopje=-38.0/12.4/62.5, Sochi=-34.7/14.2/65.7, Sofia=-39.4/10.6/59.3, Sokoto=-21.0/28.0/80.2, Split=-32.7/16.1/68.4, St. John's=-43.2/5.0/55.9, St. Louis=-34.6/13.9/65.2, Stockholm=-43.0/6.6/57.2, Surabaya=-23.9/27.1/82.1, Suva=-30.4/25.6/73.7, Suwałki=-41.1/7.2/58.5, Sydney=-32.5/17.7/73.8, Ségou=-22.0/28.0/78.7, Tabora=-32.0/23.0/70.6, Tabriz=-36.8/12.6/61.5, Taipei=-24.1/23.0/75.4, Tallinn=-47.5/6.4/55.6, Tamale=-23.0/27.9/77.2, Tamanrasset=-30.2/21.7/71.1, Tampa=-27.4/22.9/71.8, Tashkent=-32.4/14.8/63.0, Tauranga=-34.2/14.8/64.5, Tbilisi=-36.3/12.9/68.8, Tegucigalpa=-35.7/21.7/75.1, Tehran=-31.9/17.0/70.9, Tel Aviv=-30.5/20.0/75.6, Thessaloniki=-32.6/16.0/70.7, Thiès=-29.2/24.0/72.2, Tijuana=-32.1/17.8/66.1, Timbuktu=-23.0/28.0/77.3, Tirana=-33.7/15.2/68.5, Toamasina=-25.8/23.4/71.5, Tokyo=-31.6/15.4/64.2, Toliara=-26.0/24.1/78.9, Toluca=-38.6/12.4/67.5, Toronto=-43.3/9.4/60.2, Tripoli=-27.8/20.0/75.2, Tromsø=-46.4/2.9/54.7, Tucson=-26.7/20.9/75.0, Tunis=-32.6/18.4/69.3, Ulaanbaatar=-52.2/-0.4/48.4, Upington=-28.7/20.4/72.4, Vaduz=-37.9/10.1/58.4, Valencia=-40.6/18.3/69.6, Valletta=-32.7/18.8/72.5, Vancouver=-38.8/10.4/60.4, Veracruz=-26.3/25.4/75.8, Vienna=-41.8/10.4/60.2, Vientiane=-24.4/25.9/76.7, Villahermosa=-21.3/27.1/74.9, Vilnius=-47.4/6.0/61.6, Virginia Beach=-35.3/15.8/62.6, Vladivostok=-47.4/4.9/55.6, Warsaw=-42.7/8.5/56.9, Washington, D.C.=-32.9/14.6/62.4, Wau=-23.1/27.8/79.9, Wellington=-37.4/12.9/62.8, Whitehorse=-50.7/-0.1/51.7, Wichita=-36.2/13.9/65.0, Willemstad=-23.0/28.0/78.6, Winnipeg=-45.8/3.0/53.2, Wrocław=-43.2/9.6/57.6, Xi'an=-34.9/14.1/66.2, Yakutsk=-59.8/-8.8/47.1, Yangon=-20.0/27.5/76.9, Yaoundé=-23.3/23.8/72.1, Yellowknife=-54.1/-4.3/46.9, Yerevan=-35.3/12.4/61.8, Yinchuan=-40.2/9.0/56.0, Zagreb=-38.9/10.7/61.1, Zanzibar City=-21.4/26.0/75.9, Zürich=-39.2/9.3/57.2, Ürümqi=-42.2/7.4/56.6, İzmir=-32.7/17.9/68.2}"
            if (solution != expected) {
                println(solution)
                println("!! Solution was incorrect !!")
            }
        }
        println("$chunkCount took ${executionTime}ms")
    }
}
