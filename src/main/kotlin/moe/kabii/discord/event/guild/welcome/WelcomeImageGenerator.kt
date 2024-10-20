package moe.kabii.discord.event.guild.welcome

import com.twelvemonkeys.image.ResampleOp
import discord4j.core.`object`.entity.Member
import moe.kabii.LOG
import moe.kabii.command.commands.configuration.welcomer.WelcomeBannerUtil
import moe.kabii.data.mongodb.guilds.WelcomeSettings
import moe.kabii.util.extensions.stackTraceString
import moe.kabii.util.extensions.userAddress
import moe.kabii.util.formatting.GraphicsUtil
import java.awt.*
import java.awt.font.FontRenderContext
import java.awt.font.TextLayout
import java.awt.geom.Ellipse2D
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.URI
import javax.imageio.ImageIO
import kotlin.math.max

object WelcomeImageGenerator {
    private val fontDir = File("files/font/")
    val bannerRoot = File("files/bannerimage/")

    private val taglinePt = 100f
    private val taglineFont = Font.createFont(Font.TRUETYPE_FONT, File(fontDir, "Prompt-Bold.ttf")).deriveFont(taglinePt)

    private val baseFont = Font.createFont(Font.TRUETYPE_FONT, File(fontDir, "NotoSansCJK-Bold.ttc"))
    private val fallbackFont = Font("LucidaSans", Font.BOLD, 128)
    private val usernamePt = 64f
    private val textPt = 64f

    private val shadowColor = Color(0f, 0f, 0f, 0.5f)
    private val outlineStroke = 6f

    const val targetHeight = 512
    const val targetWidth = targetHeight * 2
    val dimensionStr = "${targetWidth}x$targetHeight"

    init {
        bannerRoot.mkdirs()
    }

    suspend fun generate(guildId: Long, config: WelcomeSettings, member: Member): InputStream? {
        val banners = WelcomeBannerUtil.getBanners(guildId)
        if(banners.isEmpty()) return null
        var graphics: Graphics2D? = null
        try {

            // get, load image
            val bannerImage = banners.random()
            val image = ImageIO.read(bannerImage)
            require(image.width == targetWidth && image.height == targetHeight) { "Invalid/corrupt image on file: $bannerImage ${image.width}x${image.height}" }

            graphics = image.createGraphics()
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val frc = graphics.fontRenderContext

            val textColor = Color(config.textColor())

            var y = 90f // from top to baseline first line

            val lineSpacing = 20

            if(textColor == Color.BLACK) {
                config.textOutline = false
            }
            // draw tag line
            if(config.includeTagline) {
                val tagBounds = taglineFont.getStringBounds(config.taglineValue, frc)

                val tagWidth = tagBounds.width.toFloat()
                if(tagWidth <= image.width) {
                    val xCenter = (image.width - tagWidth) / 2
                    graphics.font = taglineFont

                    if (config.textOutline) {
                        drawStringWithOutline(graphics, frc, xCenter.toDouble(), y.toDouble(), textColor, config.taglineValue)
                    } else {
                        graphics.color = shadowColor
                        graphics.drawString(config.taglineValue, xCenter - 4f, y - 4f) // drop shadow up-left offset
                        graphics.color = textColor
                        graphics.drawString(config.taglineValue, xCenter, y)
                    }
                }

                val tagMetrics = taglineFont.getLineMetrics(config.taglineValue, frc)
                y += tagMetrics.descent
            }

            // draw username
            if(config.includeUsername) {
                var username = member.userAddress()

                val fit = GraphicsUtil.fitFontHorizontal(image.width, baseFont, usernamePt, username, sidePadding = 10, minPt = 24f, fallback = fallbackFont)
                graphics.font = fit.font
                username = fit.str

                // x coord is centered
                val nameWidth = graphics.fontMetrics.stringWidth(username)
                val xCenter = (image.width - nameWidth) / 2f

                y += lineSpacing
                if (config.textOutline) {
                    drawStringWithOutline(graphics, frc, xCenter.toDouble(), y.toDouble(), textColor, username)
                } else {
                    graphics.color = shadowColor
                    graphics.drawString(username, xCenter - 2f, y - 2f) // drop shadow down-left offset
                    graphics.color = textColor
                    graphics.drawString(username, xCenter, y)
                }
            }

            // draw avatar
            if(config.includeAvatar) {
                val avatarPadding = 12
                val avatarDia = if(config.includeImageText) 256 else 320

                val avatarRad = avatarDia / 2
                val outlineD = avatarDia + 3
                val outlineR = outlineD / 2

                try {
                    val avatarUrl = URI("${member.avatarUrl}?size=256").toURL()

                    // discord usually returns 256x256 avatar - unless original was smaller
                    val raw = ImageIO.read(avatarUrl)
                    val avatar = if(raw.height == avatarDia && raw.width == avatarDia) raw else {
                        val resampler = ResampleOp(avatarDia, avatarDia, ResampleOp.FILTER_LANCZOS)
                        resampler.filter(raw, null)
                    }

                    // draw avatar in circle shape
                    y += outlineR + avatarPadding
                    val xCenter = (image.width / 2) - avatarRad
                    val yCenter = y.toInt() - avatarRad
                    val avatarShape = Ellipse2D.Double(xCenter.toDouble(), yCenter.toDouble(), avatarDia.toDouble(), avatarDia.toDouble())
                    graphics.clip(avatarShape)
                    graphics.drawImage(avatar, xCenter, yCenter, null)
                    graphics.clip = null

                    // enforce outline around avatar
                    val outlineXCenter = (image.width / 2) - outlineR
                    val outlineYCenter = y.toInt() - outlineR
                    graphics.stroke = BasicStroke(6f)
                    graphics.drawOval(outlineXCenter, outlineYCenter, outlineD, outlineD)
                    graphics.stroke = BasicStroke()
                    y += outlineR

                } catch(e: Exception) {
                    LOG.warn("Unable to load user avatar user ${member.id.asString()} for welcome banner: ${member.avatarUrl} :: ${e.message}")
                    LOG.debug(e.stackTraceString)
                }
            }

            // draw caption
            if(config.includeImageText) {
                var str = WelcomeMessageFormatter.format(member, config.imageTextValue, rich = false)

                val fit = GraphicsUtil.fitFontHorizontal(image.width, baseFont, textPt, str, sidePadding = 20, minPt = 16f, fallback = fallbackFont)
                graphics.font = fit.font
                str = fit.str

                val metrics = graphics.fontMetrics
                // x coord is centered
                val textWidth = metrics.stringWidth(str)
                val xCenter = (image.width - textWidth) / 2f

                // y coord is centered within the remaining space, but at least far enough from the avatar to fit the text
                val yRemain = image.height - y
                val yCenter = yRemain / 2
                val yFontFit = ((yRemain - metrics.height) / 2) + metrics.ascent

                y += max(yCenter, yFontFit)

                if (config.textOutline) {
                    drawStringWithOutline(graphics, frc, xCenter.toDouble(), y.toDouble(), textColor, str)
                } else {
                    graphics.color = shadowColor
                    graphics.drawString(str, xCenter - 2f, y - 2f)
                    graphics.color = textColor
                    graphics.drawString(str, xCenter, y)
                }
            }

            ByteArrayOutputStream().use { os ->
                ImageIO.write(image, "png", os)
                return ByteArrayInputStream(os.toByteArray())
            }

        } catch(e: Exception) {
            LOG.warn("Unable to generate welcome banner from $config for member $member :: ${e.message}")
            LOG.debug(e.stackTraceString)
            return null
        } finally {
            graphics?.dispose()
        }
    }

    private fun drawStringWithOutline(g2d: Graphics2D, frc: FontRenderContext, x: Double, y: Double, textColor: Color, text: String, outlineColor: Color = Color.BLACK) {

        val graphics = g2d.create() as Graphics2D
        val transform = graphics.transform
        transform.translate(x, y)
        graphics.transform(transform)
        graphics.color = outlineColor
        val textLayout = TextLayout(text, graphics.font, frc)
        val shape = textLayout.getOutline(null)
        graphics.stroke = BasicStroke(outlineStroke)
        graphics.draw(shape)
        graphics.color = textColor
        graphics.fill(shape)
    }
}