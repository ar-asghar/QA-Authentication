package com.tappayments.automation.qaauthentication.authflow;

import com.tappayments.automation.qaauthentication.App;
import com.tappayments.automation.qaauthentication.base.RestAssuredClient;
import com.tappayments.automation.qaauthentication.config.ConfigManager;
import com.tappayments.automation.qaauthentication.model.CardRequest;
import com.tappayments.automation.qaauthentication.model.ChargeRequest;
import com.tappayments.automation.qaauthentication.model.TransactionRequest;
import com.tappayments.automation.qaauthentication.requests.AuthenticationTransactionRequest;
import com.tappayments.automation.qaauthentication.utils.AppConstants;
import com.tappayments.automation.qaauthentication.utils.AppUtils;
import com.tappayments.automation.utils.CommonAutomationUtils;
import io.github.bonigarcia.wdm.WebDriverManager;
import io.restassured.internal.http.ResponseParseException;
import io.restassured.response.Response;
import org.apache.http.auth.AUTH;
import org.openqa.selenium.*;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.Select;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

public class AuthenticationFlow {

    private static final Map<String, String> PROVIDER_TERMINAL_MAP = Map.of(
            "MPGS", "ter_p3F64020211320j6XQ1002702"
    );
    private static final Map<String, List<Long>> CARD_NUMBERS = Map.of(
            "MPGS", List.of(5123450000000008L)
    );

//    private static final Map<String, String> PROVIDER_TERMINAL_MAP = Map.of(
//            "MPGS", "ter_p3F64020211320j6XQ1002702",
//            "CYBERSOURCE","ter_df5cf64609664377b57ef31c"
//    );
//    private static final Map<String, List<Long>> CARD_NUMBERS = Map.of(
//            "MPGS", List.of(5123450000000008L, 4440000009900010L),
//            "CYBERSOURCE", List.of(4000000000002503L, 4000000000002503L)
//    );

// ---------------MPGS
// id = challengeFrame
//    /html/body/div/div/div[3]/center/form/table/tbody/tr[4]/td/input

//    id = redirectTo3ds1Frame
//    /html/body/div/div/div[3]/center/form/table/tbody/tr[13]/td/input

//  --------------------cybersource
//  id = step-up-iframe(parent) => inner iframe get by 0 index
//  /html/body/div/section[1]/div[2]/form[1]/input[2]  <> submit button
//  /html/body/div/section[1]/div[2]/form[1]/input[1] <> otp input field

// ------MPGS
// Mastercard:
// Challenge: 5123450000000008
// Frictionless: 5123456789012346
// Visa:
// Challenge: 4440000009900010
// Frictionless: 4440000042200014
// ------Cybersource
// VISA:
// Challenge: 4000000000002503
// Frictionless: 4000000000002701
// Mastercard:
// Challenge: 5200000000002151
// Frictionless: 5200000000002276

// ------Cybersource
// key: test
// OMR currency
// VISA:
// Challenge: 4000000000002503
// Frictionless: 4000000000002701
// Mastercard:
// Challenge: 5200000000002151
// Frictionless: 5200000000002276


    private static final String AUTHORIZATION_TOKEN = ConfigManager.getPropertyValue("authorizationToken");
    private static final String MID = ConfigManager.getPropertyValue("merchantId");
    private static final String AUTH_FLOW_ENDPOINT = ConfigManager.getPropertyValue("stagingBaseUri");
    private static final String CHARGE_API_ENDPOINT = ConfigManager.getPropertyValue(AppConstants.TAP_BASE_URI) + AppConstants.CHARGE_URI;

    public static void main(String[] args) {

        for (Map.Entry<String, String> entry : PROVIDER_TERMINAL_MAP.entrySet()) {

            String provider = entry.getKey();
            String terminalId = entry.getValue();
            List<Long> cardNumbers = CARD_NUMBERS.get(provider);

            for (Long cardNumber : cardNumbers) {
                transactionAuthentication(cardNumber, provider, terminalId);
            }
        }
    }

    private static void transactionAuthentication(Long cardNumber, String provider, String terminalId) {

        TransactionRequest transactionRequest = AppUtils.transactionRequestInstance();
        Response authenticationResponse = performTransactionAuthentication(transactionRequest, cardNumber, provider, terminalId);
        String authUrl = authenticationResponse.jsonPath().getString(AppConstants.AUTHENTICATION + "." + AppConstants.URL);

        if (authUrl != null && !authUrl.isEmpty()) {

            WebDriverManager.chromedriver().setup();
            WebDriver driver = new ChromeDriver();
            driver.get(authUrl);

            System.out.println("Calling charge API for provider : " + authenticationResponse.jsonPath().getString(AppConstants.ROUTING + "." + AppConstants.PROVIDER) + " & card token : " + authenticationResponse.jsonPath().getString(AppConstants.SOURCE + "." + AppConstants.ID));

            handleChallengeModeMpgs(driver);  // Assuming you are in challenge mode, handle it here
            waitForRedirectAndQuit(driver, transactionRequest.getRedirect().getUrl());

            String authId = authenticationResponse.jsonPath().getString(AppConstants.ID);
            String sourceId = authenticationResponse.jsonPath().getString(AppConstants.SOURCE + "." + AppConstants.ID);

            Response chargeApiResponse = getChargeApiResponse(authId, sourceId);
            System.out.println("==================CHARGE API RESPONSE==================");
            System.out.println("Charge id: " + chargeApiResponse.jsonPath().getString(AppConstants.ID));
            System.out.println("Status: " + chargeApiResponse.jsonPath().getString("status"));
            System.out.println("======================================================");
        }
    }

    private static Response performTransactionAuthentication(TransactionRequest transactionRequest, Long cardNumber, String provider, String terminalId) {

        String sessionToken = RestAssuredClient.generateSessionToken(AUTHORIZATION_TOKEN);
        CardRequest cardRequest = AppUtils.createCardRequest();
        cardRequest.getCard().setNumber(cardNumber);

        String cardDetailToken = RestAssuredClient.generateCardDetailToken(cardRequest);
        return waitForAuthUrl(sessionToken, cardDetailToken, transactionRequest, provider, terminalId);
    }

    public static Response waitForAuthUrl(String sessionToken, String cardDetailToken, TransactionRequest transactionRequest, String provider, String terminalId) {

        String authUrl = "";
        int retryCount = 5;
        int retryInterval = 2000; // 2 seconds
        Response response = null;
        for (int i = 0; i < retryCount; i++) {
            response = performTransactionAuthentication(sessionToken, cardDetailToken, transactionRequest, provider, terminalId);
            authUrl = response.jsonPath().getString(AppConstants.AUTHENTICATION + "." + AppConstants.URL);
            if (authUrl != null && !authUrl.isEmpty()) {
                break; // Exit loop once the auth URL is obtained
            } else {
                try {
                    System.out.println("Auth URL not available, retrying...");
                    Thread.sleep(retryInterval);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }

        return response;
    }

    private static Response performTransactionAuthentication(String sessionToken, String cardDetailToken, TransactionRequest transactionRequest, String provider, String terminalId) {

        System.out.println("PROVIDER: " + provider + " & Terminal ID: " + terminalId);
        transactionRequest.getTransaction().setId(UUID.randomUUID().toString());
        transactionRequest.getSource().setId(cardDetailToken);
        transactionRequest.getRouting().setProvider(provider);
        transactionRequest.getRouting().setTerminalId(terminalId);
        transactionRequest.getRedirect().setUrl("https://yourwebsite.com/redirect_url");

        String body = CommonAutomationUtils.stringToJson(transactionRequest);
        String updatedBody = CommonAutomationUtils.modifyJson(body, "DELETE", "customer.id", null);

        return AuthenticationTransactionRequest.postMethodResponseHeaders(
                Map.of(
                        AppConstants.CONTENT_TYPE, ConfigManager.getPropertyValue(AppConstants.CONTENT_TYPE_VALUE),
                        AppConstants.AUTHORIZATION, AppConstants.BEARER + AUTHORIZATION_TOKEN,
                        AppConstants.MERCHANT_ID, MID,
                        AppConstants.LIVE_MODE, "false",
                        AppConstants.SESSION_TOKEN, sessionToken),
                updatedBody,
                AUTH_FLOW_ENDPOINT
        );
    }

    public static void waitForRedirectAndQuit(WebDriver driver, String expectedUrl) {
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(80));

        System.out.println(expectedUrl);
        // Wait for redirection to be completed by waiting until the current URL contains the expected part of the URL
        wait.until(ExpectedConditions.urlContains(expectedUrl));

        // Optionally, you can wait for some element to be loaded on the final page
        // WebElement finalPageElement = wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("finalPageElement")));
        System.out.println("Redirection complete. Closing browser...");
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        driver.quit();
    }

    private static Response getChargeApiResponse(String authId, String sourceId) {

        ChargeRequest chargeRequest = AppUtils.chargeRequestInstance();
        chargeRequest.getAuthentication().setId(authId);
        chargeRequest.getSource().setId(sourceId);

        String chargeRequestBody = CommonAutomationUtils.stringToJson(chargeRequest);

        return RestAssuredClient.postMethodResponseHeaders(
                Map.of(
                        AppConstants.CONTENT_TYPE, ConfigManager.getPropertyValue(AppConstants.CONTENT_TYPE_VALUE),
                        AppConstants.AUTHORIZATION, AppConstants.BEARER + AUTHORIZATION_TOKEN
                ),
                chargeRequestBody,
                CHARGE_API_ENDPOINT
        );
    }

    public static void handleChallengeModeMpgs(WebDriver driver) {
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(60), Duration.ofMillis(1000));
        JavascriptExecutor js = (JavascriptExecutor) driver;
        try {
            driver.switchTo().frame("challengeFrame");
            System.out.println("Switched to challengeFrame.");
        } catch (NoSuchFrameException e) {
            System.out.println("Frame not available. Proceeding without switching to the frame.");
        }
        try {
            js.executeScript("document.getElementById('acssubmit').click();");
            System.out.println("Submit button clicked via JavaScript.");
        } catch (Exception e) {
            System.out.println("Failed to click submit button via JavaScript.");
            e.printStackTrace();
        }
        try {
            WebElement submitButton = driver.findElement(By.id("acssubmit"));
            submitButton.click();
            System.out.println("Submit button clicked via Selenium.");
        } catch (Exception e) {
            System.out.println("Failed to click the submit button via Selenium.");
            e.printStackTrace();
        }
    }

//    public static void handleChallengeModeMpgs(WebDriver driver) {
//
//        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(60));
//        JavascriptExecutor js = (JavascriptExecutor) driver;
//
//        // Wait for the methodFrame iframe to appear and switch to it
//        wait.until(ExpectedConditions.frameToBeAvailableAndSwitchToIt(By.id("methodFrame")));
//        System.out.println("Switched to methodFrame iframe.");
//
////        // Wait for methodFrame to disappear
////        wait.until(ExpectedConditions.invisibilityOfElementLocated(By.id("challengeFrame")));
//        //System.out.println("methodFrame is no longer visible, waiting for challengeFrame.");
//
//        // Switch back to the main content before waiting for the next iframe
////        driver.switchTo().defaultContent();
//
//        System.out.println("switch to default content");
//        // Try to fetch the challengeFrame using JavaScript polling
//
//        wait.until(driver1 -> {
//
//            System.out.println("Polling for challengeFrame using JavaScript...");
//            WebElement challengeFrame = (WebElement) js.executeScript("return document.getElementById('challengeFrame');");
//            System.out.println("challengeFrame : " + challengeFrame);
////            WebElement challengeFrame = driver1.findElement(By.id("challengeFrame"));
////            System.out.println("challengeFrame : " + challengeFrame);
//
//            if (challengeFrame != null) {
//
//                System.out.println("challengeFrame displayed: " + challengeFrame.isDisplayed());
//                System.out.println("Before switch to challengeFrame using JavaScript polling.");
//                System.out.println(driver1);
//                System.out.println(driver1.getTitle());
//                System.out.println(driver1.getPageSource());
//                System.out.println("after some delay check iframe");
////                driver1.switchTo().frame(challengeFrame);
//                wait.until(ExpectedConditions.frameToBeAvailableAndSwitchToIt(By.id("challengeFrame")));
//                System.out.println("Switched to challengeFrame using JavaScript polling.");
//                return true;  // Exit the wait loop once the frame is found and switched to
//            } else {
//                return false;  // Keep waiting if the frame is not yet available
//            }
//        });
//
//        System.out.println("After Switched to challengeFrame using JavaScript polling.");
//
//        try {
//            Thread.sleep(1000);
//        } catch (InterruptedException e) {
//            throw new RuntimeException(e);
//        }
//
//        System.out.println("after some delay check dropdown");
//        // Now wait for the dropdown inside challengeFrame
//        WebElement authResultDropdown = wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("selectAuthResult")));
//        System.out.println("Dropdown is visible.");
//
//        // Create a Select object to handle the dropdown
//        Select select = new Select(authResultDropdown);
//        select.selectByValue("AUTHENTICATED");
//        System.out.println("Option 'AUTHENTICATED' selected.");
//
//        // Locate the submit button and click it
//        WebElement submitButton = wait.until(ExpectedConditions.elementToBeClickable(By.id("acssubmit")));
//        submitButton.click();
//        System.out.println("Submit button clicked. Challenge flow submitted successfully.");
//
//
////        List<WebElement> iframes = driver.findElements(By.tagName("iframe"));
////        System.out.println("Number of iframes: " + iframes.size());
////        // Wait for the methodFrame iframe to appear
////        wait.until(ExpectedConditions.frameToBeAvailableAndSwitchToIt(By.id("methodFrame")));
////        System.out.println("Switched to methodFrame iframe.");
////
////        // Now wait for methodFrame to disappear (indicating the challengeFrame will load next)
////        wait.until(ExpectedConditions.invisibilityOfElementLocated(By.id("methodFrame")));
////        System.out.println("methodFrame is no longer visible, waiting for challengeFrame.");
////
////        // Switch back to the main content before waiting for the next iframe
////        driver.switchTo().defaultContent();
////
////        iframes = driver.findElements(By.tagName("iframe"));
////        System.out.println("Number of iframes: " + iframes.size());
////        // Wait for challengeFrame to be available and switch to it
////        wait.until(ExpectedConditions.frameToBeAvailableAndSwitchToIt(By.id("challengeFrame")));
////        System.out.println("Switched to challengeFrame iframe.");
////
////        // Now wait for the dropdown inside challengeFrame
////        WebElement authResultDropdown = wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("selectAuthResult")));
////        System.out.println("Dropdown is visible.");
////
////        // Create a Select object to handle the dropdown
////        Select select = new Select(authResultDropdown);
////
////        // Select the option "AUTHENTICATED" from the dropdown
////        select.selectByValue("AUTHENTICATED");
////        System.out.println("Option 'AUTHENTICATED' selected.");
////
////        // Locate the submit button and click it
////        WebElement submitButton = wait.until(ExpectedConditions.elementToBeClickable(By.id("acssubmit")));
////        submitButton.click();
////        System.out.println("Submit button clicked. Challenge flow submitted successfully.");
////    }
//
//
////    public static void handleChallengeModeMpgs(WebDriver driver) {
////        System.out.println("call handle challenge mdoe");
////        // Add WebDriverWait to wait for elements in challenge mode
////        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(10));
////        System.out.println("Waiting for the page to load...");
//////        JavascriptExecutor js = (JavascriptExecutor) driver;
//////        Boolean pageIsReady = js.executeScript("return document.readyState").equals("complete");
//////
//////        System.out.println("check page read...");
//////        if (pageIsReady) {
//////            System.out.println("Page is fully loaded");
//////            List<WebElement> iframes = driver.findElements(By.tagName("iframe"));
//////            System.out.println("Number of iframes: " + iframes.size());
//////        } else {
//////            System.out.println("Page is not fully loaded yet.");
//////        }
//////
//////        List<WebElement> iframes = driver.findElements(By.tagName("iframe"));
//////        System.out.println("Number of iframes: " + iframes.size());
//////
//////        for (WebElement iframe : iframes) {
//////            System.out.println("iframe id: " + iframe.getAttribute("id"));
//////            System.out.println("iframe name: " + iframe.getAttribute("name"));
//////        }
////
////        System.out.println("Number of iframes on the page: " + driver.findElements(By.tagName("iframe")).size());
////        // Switch to the iframe by ID
////            wait.until(ExpectedConditions.frameToBeAvailableAndSwitchToIt(By.id("methodFrame")));
////        System.out.println("Switched to the challenge frame.");
////        try {
////            Thread.sleep(20000);
////        } catch (InterruptedException e) {
////            throw new RuntimeException(e);
////        }
////        System.out.println("Number of iframes on the page: " + driver.findElements(By.tagName("iframe")).size());
////        wait.until(ExpectedConditions.frameToBeAvailableAndSwitchToIt(By.id("challengeFrame")));
////        System.out.println("Switched to the challenge frame.");
////
////        // Wait for the dropdown to be visible
////        WebElement authResultDropdown = wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("selectAuthResult")));
////
////        // Create a Select object to handle the dropdown
////        Select select = new Select(authResultDropdown);
////
////        // Select the option "AUTHENTICATED" from the dropdown (or whichever option you want)
////        select.selectByValue("AUTHENTICATED");  // Or select.selectByVisibleText("(Y) Authentication/Account Verification Successful");
////
////        // Locate the submit button and click it
////        WebElement submitButton = driver.findElement(By.id("acssubmit"));
////
////        // Click the submit button
////        submitButton.click();
////
////        System.out.println("Challenge flow submitted successfully.");
////    }
//
//    }
}
