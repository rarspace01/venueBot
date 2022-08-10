package venuebot

import io.github.bonigarcia.wdm.WebDriverManager
import org.openqa.selenium.By
import org.openqa.selenium.WebDriver
import org.openqa.selenium.chrome.ChromeOptions
import org.openqa.selenium.support.ui.ExpectedConditions
import org.openqa.selenium.support.ui.Select
import org.openqa.selenium.support.ui.WebDriverWait
import java.lang.Thread.sleep
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private const val LOGIN_PAGE = "https://www.dresden.de/apps_ext/StrassenmusikApp_en/login"

object Main {

    @JvmStatic
    fun main(args: Array<String>) {

        val started = Instant.now()

        val username = PropertiesHelper.getProperty("EMAIL")
        val password = PropertiesHelper.getProperty("PASSWORD")

        if (username?.isBlank() == true || password?.isBlank() == true) println("username or password not provided")

        println("Hello World!")

        WebDriverManager.chromedriver().setup()

        //Initiating your chromedriver
        val chromeOptions = ChromeOptions()
        //chromeOptions.addExtensions(File("buster.crx"))

        val webDriverManager = WebDriverManager.chromedriver().browserInDocker().enableVnc()
        val driver: WebDriver = webDriverManager.create()
        while (Duration.between(started, Instant.now()).toHours() < 1) {
            driver.manage().deleteAllCookies()

            println(webDriverManager.dockerNoVncUrl)
            println(webDriverManager.dockerVncUrl)

            driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(10))

            driver.manage().window().maximize()
            val webDriverWait = WebDriverWait(driver, Duration.ofSeconds(5))

            loginIntoPage(driver, webDriverWait, username, password)

            SlotService().getSlotsToBeBooked().map {
                bookOneSlot(driver, webDriverWait, it)
            }

        }
        driver.close()
        webDriverManager.quit()
    }

    private fun bookOneSlot(driver: WebDriver, webDriverWait: WebDriverWait, slot: Slot) {
        val localDateForBooking = deductDateFromWeekday(slot.weekday)

        println("trying to book slot: $slot for $localDateForBooking")

        // booking start page
        driver["https://www.dresden.de/apps_ext/StrassenmusikApp_en/applicant"]

        val addBookingButton = By.cssSelector(".add")
        webDriverWait.until(ExpectedConditions.presenceOfElementLocated(addBookingButton))
        driver.findElements(addBookingButton)[1].click()

        // amplifieer data
        val nextButton = By.className("next")
        webDriverWait.until(ExpectedConditions.urlMatches("create-booking-userdata"))
        webDriverWait.until(ExpectedConditions.presenceOfElementLocated(nextButton))
        driver.findElement(nextButton).click()

        // add a new reservation
        val addButton = By.className("add")
        webDriverWait.until(ExpectedConditions.presenceOfElementLocated(addButton)).click()

        // switch to time slot on venue mode
        val radioSelect = By.cssSelector("label[for*='FREE_TIME']")
        driver.findElement(radioSelect).click()

        webDriverWait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector("iframe")))

        // select venue
        val venueSelector = By.cssSelector("select")
        val venueSelectorElement = driver.findElement(venueSelector)
        val venueDropdown = Select(venueSelectorElement)
        venueDropdown.options.map { if (it.text.contains("${slot.lot} - ")) it.click() }

        // check for slots, select corresponding slots
        val slotsTable = By.className("list-choose-container")
        webDriverWait.until(ExpectedConditions.presenceOfElementLocated(slotsTable))

        val tableRecordSelector = By.cssSelector("table > tbody > tr")
        val slotsEntries = driver.findElements(tableRecordSelector)

        val selectedSlot = slotsEntries.filter {
            if (it.text.contains(localDateForBooking.year.toString())) {
                it.findElement(By.xpath(".//td[2]/div[2]")).text.contains("${localDateForBooking.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))} ${slot.localTime}")
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
                println("Slot not available")
            }
        }

        if (selectedSlot.isEmpty() || !foundSlot) {
            println("Slot not found")
        } else {
            // nextAfter Slot
            val nextButtonSlots = By.className("next")
            driver.findElement(nextButtonSlots).click()

            // confirmBooking
            val confirmBooking = By.className("next")
            driver.findElement(confirmBooking).click()

            println("should be booked")
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
    ) {
        //open browser with desired URL
        driver[LOGIN_PAGE]

        //driver.navigate().to("https://www.dresden.de/apps_ext/StrassenmusikApp_en/login")
        val slotsToBeBooked = SlotService().getSlotsToBeBooked()


        val loginButton = By.name("submit")
        val usernameField = By.name("username")
        val passwordField = By.name("password")
        webDriverWait.until(ExpectedConditions.presenceOfElementLocated(loginButton))
        driver.findElement(usernameField).sendKeys(username)
        driver.findElement(passwordField).sendKeys(password)
        driver.findElement(loginButton).click()

        val cookieAllowButton = By.cssSelector("a.cc-btn.cc-allow")

        webDriverWait.until(ExpectedConditions.presenceOfElementLocated(cookieAllowButton))

        driver.findElement(cookieAllowButton).click()
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
