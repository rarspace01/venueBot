package venuebot

import io.github.bonigarcia.wdm.WebDriverManager
import org.openqa.selenium.By
import org.openqa.selenium.WebDriver
import org.openqa.selenium.support.ui.ExpectedConditions
import org.openqa.selenium.support.ui.Select
import org.openqa.selenium.support.ui.WebDriverWait
import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.Thread.sleep
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread


private const val LOGIN_PAGE = "https://www.dre" + "sden.de/apps_ext/Stras" + "senmusikApp_en/login"

object Main {

    @JvmStatic
    fun main(args: Array<String>) {

        val started = Instant.now()

        val username = PropertiesHelper.getProperty("EMAIL")
        val password = PropertiesHelper.getProperty("PASSWORD")
        val durationString = PropertiesHelper.getProperty("DURATION")
        val bucketString = PropertiesHelper.getProperty("BUCKET")
        val localString = PropertiesHelper.getProperty("LOCAL")
        val durationOfBookingTime = Duration.ofMinutes(durationString?.toLong() ?: 5)

        val bucket = if (bucketString?.isNotBlank() == true) bucketString.toInt() else 0

        if (username?.isBlank() == true || password?.isBlank() == true) println("username or password not provided")

        println("running on: [${HttpHelper.externalHostname()}] for $durationOfBookingTime ${if (bucket > 0) "for bucket $bucket" else ""}")

        val runHeadless = localString?.isBlank() != false

        val numberOfThreads = if (runHeadless) 5 else 1

        for (i in 1..numberOfThreads) {
            thread(start = true) {
                println("${Thread.currentThread()} has started.")
                startWebDriver(runHeadless, username, password, bucket, started, durationOfBookingTime)
            }
            sleep(1000)
        }

    }

    private fun startWebDriver(
        runHeadless: Boolean,
        username: String?,
        password: String?,
        bucket: Int,
        started: Instant?,
        durationOfBookingTime: Duration?
    ) {
        WebDriverManager.chromedriver().setup()

        //Initiating your chromedriver
        //val chromeOptions = ChromeOptions()
        //chromeOptions.addExtensions(File("buster.crx"))

        val webDriverManager: WebDriverManager = if (runHeadless) {
            WebDriverManager.chromedriver().browserInDocker().enableVnc()
        } else {
            WebDriverManager.chromedriver()
        }

        val driver: WebDriver = webDriverManager.create()
        println(webDriverManager.dockerNoVncUrl)
        println(webDriverManager.dockerVncUrl)
        driver.manage().deleteAllCookies()


        driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(10))

        driver.manage().window().maximize()
        val webDriverWait = WebDriverWait(driver, Duration.ofSeconds(10))

        loginIntoPage(driver, webDriverWait, username, password)
        val slotsToBeBooked = SlotService().getSlotsToBeBooked()
        val slotsToBeBookedOnThisMachine = if (bucket == 0) slotsToBeBooked else if (slotsToBeBooked.size >= bucket) {
            listOf(slotsToBeBooked[bucket - 1])
        } else emptyList()
        if (slotsToBeBookedOnThisMachine.isEmpty()) println("no slots to be booked on this machine, canceling run")
        val counter = AtomicLong()
        while (Duration.between(started, Instant.now()) < durationOfBookingTime && slotsToBeBookedOnThisMachine.isNotEmpty()) {
            counter.incrementAndGet()
            if((counter.get() % 10) == 0L) {
                printMemoryInfo()
            }
            slotsToBeBookedOnThisMachine.map {
                val start = Instant.now()
                bookOneSlot(driver, webDriverWait, it)
                println("booking slot took: ${Duration.between(start, Instant.now())}")
            }
        }
        driver.close()
        webDriverManager.quit()
    }

    private fun printMemoryInfo() {
        val runtime = Runtime.getRuntime()

        val br = BufferedReader(
            InputStreamReader(runtime.exec("free -m").inputStream)
        )

        val text = br.readText().split("\n".toRegex())
        val memLine = text.first { it.contains("Mem:") }

        val memInfoList: List<String> = memLine.split("\\s+".toRegex()).dropLastWhile { it.isEmpty() }
        val totalSystemMemory = memInfoList[1].toInt()
        val totalSystemUsedMemory = memInfoList[2].toInt()
        val totalSystemFreeMemory = memInfoList[3].toInt()
        println("MEM : USED: $totalSystemUsedMemory, FREE: $totalSystemFreeMemory MB, TOTAL: $totalSystemMemory MB")
    }

    private fun bookOneSlot(driver: WebDriver, webDriverWait: WebDriverWait, slot: Slot) {
        try {
            val localDateForBooking = deductDateFromWeekday(slot.weekday)

            println("${Thread.currentThread()} trying to book slot: $slot for $localDateForBooking")

            // booking start page

            // append data
            val nextButton = By.className("next")
            driver.navigate().to("https://www.dres" + "den.de/apps_ext/Stras" + "senmusikApp_en/create-booking-userdata")
            driver.findElement(nextButton).click()

            // add a new reservation
            val addButton = By.className("add")
            driver.findElement(addButton).click()
            // Select time slot
            val periodSelector = By.cssSelector("select")
            val periodSelectorElement = driver.findElement(periodSelector)
            val periodDropdown = Select(periodSelectorElement)
            periodDropdown.options.filter { it.text.contains("${localDateForBooking.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))} ${slot.localTime}") }
                .map { it.click() }
                .let {
                    if (it.isEmpty()) {
                        println("${Thread.currentThread()} $slot time not available anymore")
                        return
                    }
                }
            // check for venues
            val slotsTable = By.className("responsiveTable")
            webDriverWait.until(ExpectedConditions.presenceOfElementLocated(slotsTable))
            val tableRecordSelector = By.cssSelector("table > tbody > tr")
            val slotsEntries = driver.findElements(tableRecordSelector)

            val selectedSlot = slotsEntries.filter {
                if (it.text.contains("${slot.lot} - ")) {
                    it.findElement(By.xpath(".//td[2]/div[2]")).text.contains("${slot.lot} - ")
                } else false
            }
            var foundSlot = false
            selectedSlot.map {
                val slotRadioButton = it.findElement(By.xpath(".//td[1]/div[2]"))
                val statusText = it.findElement(By.xpath(".//td[3]/div[2]"))
                if (statusText.text.contains("available")) {
                    slotRadioButton.click()
                    foundSlot = true
                } else {
                    println("${Thread.currentThread()} $slot not available - [${statusText.text}]")
                }
            }

            if (selectedSlot.isEmpty() && !foundSlot) {
                println("${Thread.currentThread()} $slot not found")
            } else if (foundSlot) {
                // nextAfter Slot found
                val nextButtonSlots = By.className("next")
                driver.findElement(nextButtonSlots).click()

                webDriverWait.until(ExpectedConditions.presenceOfElementLocated(By.className("add")))

                if (!driver.pageSource.contains("list of all venues available")) {
                    println("Not the page we page: ${driver.currentUrl} with ${driver.pageSource}")
                }

                // confirmBooking
                val confirmBooking = By.className("next")
                driver.findElement(confirmBooking).click()

                println("${Thread.currentThread()} $slot should be booked")
            }
        } catch (exception: Exception) {
            println("Exception in: $exception stack:\n${exception.stackTrace} on page: ${driver.currentUrl} with ${driver.pageSource}")
        }
    }

    private fun deductDateFromWeekday(weekday: DayOfWeek): LocalDate {
        val today = LocalDate.now()
        return if (today.dayOfWeek == weekday) {
            today
        } else {
            val differenceInDays = ((weekday.value + 7) - today.dayOfWeek.value).let {
                if (it > 7) it - 7 else it
            }
            today.plusDays(differenceInDays.toLong())
        }
    }

    private fun loginIntoPage(
        driver: WebDriver, webDriverWait: WebDriverWait, username: String?, password: String?
    ): Boolean {
        //open browser with desired URL
        driver[LOGIN_PAGE]

        val loginButton = By.name("submit")
        val usernameField = By.name("username")
        val passwordField = By.name("password")
        webDriverWait.until(ExpectedConditions.presenceOfElementLocated(loginButton))
        driver.findElement(usernameField).sendKeys(username)
        driver.findElement(passwordField).sendKeys(password)
        driver.findElement(loginButton).click()

        val cookieAllowButton = By.cssSelector("a.cc-btn.cc-allow")

        driver.findElement(cookieAllowButton).click()

        return true
    }

    private fun solveRecaptcha(driver: WebDriver, webDriverWait: WebDriverWait) {
        // try recaptcha
        // wait for recaptcha recaptcha-anchor
        val reCaptchaFrame = By.cssSelector("iframe")
        driver.switchTo().defaultContent()
        webDriverWait.until(ExpectedConditions.frameToBeAvailableAndSwitchToIt(reCaptchaFrame))
        val recaptchaBox = By.className("recaptcha-checkbox-border")
        webDriverWait.until(ExpectedConditions.presenceOfElementLocated(recaptchaBox))
        sleep(200)
        driver.findElement(recaptchaBox).click()

        // check if challenged
        driver.switchTo().defaultContent()
        driver.switchTo().frame(0)
        if (driver.findElements(By.id("recaptcha-accessible-status")).size > 0) {
            println("recaptcha challenge")
        }

        // complete booking
        sleep(999999)

        val completeBooking = By.className("next")
        webDriverWait.until(ExpectedConditions.presenceOfElementLocated(completeBooking)).click()
    }

}
