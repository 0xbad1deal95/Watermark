package watermark

import java.awt.Color
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import kotlin.io.path.Path
import kotlin.system.exitProcess

enum class ImageType { IMAGE, WATERMARK }

data class Image constructor(
    val fileName: String,
    val imageType: ImageType,
) {
    data class Pixel(val x: Int, val y: Int, val color: Color)

    private val imageFile = Path(fileName).toFile()

    private val imageBuffer by lazy { ImageIO.read(imageFile) }

    val width: Int get() = imageBuffer.width
    val height: Int get() = imageBuffer.height

    private val numColorComponents: Int get() = imageBuffer.colorModel.numColorComponents

    private val pixelSize: Int get() = imageBuffer.colorModel.pixelSize

    val isTranslucent get() = imageBuffer.transparency == 3

    val pixels
        get() = IntRange(0, width - 1)
            .asSequence()
            .flatMap { x -> IntRange(0, height - 1).asSequence().map { x to it } }
            .map { (x, y) -> Pixel(x, y, Color(imageBuffer.getRGB(x, y), isTranslucent)) }

    init {
        when {
            !imageFile.exists() -> println("The file $fileName doesn't exist.").also { exitProcess(1) }

            numColorComponents != 3 ->
                println("The number of ${imageType.name.lowercase()} color components isn't 3.")
                    .also { exitProcess(1) }

            pixelSize != 24 && pixelSize != 32 ->
                println("The ${imageType.name.lowercase()} isn't 24 or 32-bit.").also { exitProcess(1) }
        }
    }

    companion object {
        fun blendPixels(original: Pixel, mask: Pixel, weight: Int): Color = Color(
            (weight * mask.color.red + (100 - weight) * original.color.red) / 100,
            (weight * mask.color.green + (100 - weight) * original.color.green) / 100,
            (weight * mask.color.blue + (100 - weight) * original.color.blue) / 100,
        )
    }
}

fun main() {
    println("Input the image filename:")
    val image = Image(readln(), ImageType.IMAGE)

    println("Input the watermark image filename:")
    val watermark = Image(readln(), ImageType.WATERMARK)

    if (image.width < watermark.width || image.height < watermark.height)
        println("The watermark's dimensions are larger.").also { exitProcess(1) }

    val useWaterMarkAlphaChannel = if (watermark.isTranslucent) {
        println("Do you want to use the watermark's Alpha channel?")
        readln().equals("yes", ignoreCase = true)
    } else false

    val useTransparencyColor = if (!watermark.isTranslucent) {
        println("Do you want to set a transparency color?")
        readln().equals("yes", ignoreCase = true)
    } else false

    val transparencyColor = if (useTransparencyColor) {
        println("Input a transparency color ([Red] [Green] [Blue]):")
        val (r, g, b) = readln()
            .split(" ")
            .mapNotNull(String::toIntOrNull)
            .also {
                val inputsAreValid = it.all { x -> x in 0..255 }
                require(it.size == 3 && inputsAreValid) {
                    println("The transparency color input is invalid.")
                    exitProcess(1)
                }
            }
        Color(r, g, b)
    } else Color.black

    println("Input the watermark transparency percentage (Integer 0-100):")

    val weight = try {
        readln().toInt().also {
            require(it in 0..100) {
                println("The transparency percentage is out of range.")
                exitProcess(1)
            }
        }
    } catch (e: NumberFormatException) {
        println("The transparency percentage isn't an integer number.")
        exitProcess(1)
    }

    println("Choose the position method (single, grid):")

    val position = readln().lowercase().also {
        require(it in arrayOf("single", "grid")) {
            println("The position method input is invalid.")
            exitProcess(1)
        }
    }

    val diffX = image.width - watermark.width
    val diffY = image.height - watermark.height
    val (singleX, singleY) = if (position == "single") {
        println("Input the watermark position ([x 0-$diffX] [y 0-$diffY]):")
        readln()
            .split(" ")
            .mapNotNull(String::toIntOrNull)
            .also {
                require(it.size == 2) {
                    println("The position input is invalid.")
                    exitProcess(1)
                }
            }.also {
                val (x, y) = it
                require(x in 0..diffX && y in 0..diffY) {
                    println("The position input is out of range.")
                    exitProcess(1)
                }
            }.toIntArray()
    } else intArrayOf(0, 0)

    println("Input the output image filename (jpg or png extension):")
    val outputFileName = readln()
    val outputFile = Path(outputFileName).toFile()

    if (!outputFile.name.endsWith(".jpg") && !outputFile.name.endsWith(".png")) {
        println("""The output file extension isn't "jpg" or "png".""")
        exitProcess(1)
    }

    val outPutImage = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_RGB)

    if (position == "single") image.pixels.groupBy {
        it.x in singleX until (singleX + watermark.width) && it.y in singleY until  (singleY + watermark.height)
    }.forEach { (shouldBlendPixels, imagePixels) ->
        if (!shouldBlendPixels) imagePixels.forEach { outPutImage.setRGB(it.x, it.y, it.color.rgb) }
        else imagePixels
            .asSequence()
            .zip(watermark.pixels)
            .map { (imagePixel, watermarkPixel) ->
                when {
                    useWaterMarkAlphaChannel && watermarkPixel.color.alpha == 0 -> imagePixel
                    useTransparencyColor && watermarkPixel.color.rgb == transparencyColor.rgb -> imagePixel
                    else -> imagePixel.copy(color = Image.blendPixels(imagePixel, watermarkPixel, weight))
                }
            }.forEach { outPutImage.setRGB(it.x, it.y, it.color.rgb) }
    } else {
        val watermarkPixels = watermark
            .pixels
            .groupBy { it.x to it.y }
            .mapValues { it.value.first() }

        image.pixels.forEach {
            val key = it.x % watermark.width to it.y % watermark.height
            val watermarkPixel = watermarkPixels[key]!!
            val pixel = when {
                useWaterMarkAlphaChannel && watermarkPixel.color.alpha == 0 -> it
                useTransparencyColor && watermarkPixel.color.rgb == transparencyColor.rgb -> it
                else -> it.copy(color = Image.blendPixels(it, watermarkPixel, weight))
            }

            outPutImage.setRGB(pixel.x, pixel.y, pixel.color.rgb)
        }
    }

    ImageIO.write(
        outPutImage,
        outputFileName.substringAfter('.'),
        outputFile,
    ).also {
        println("The watermarked image $outputFileName has been created.")
    }
}