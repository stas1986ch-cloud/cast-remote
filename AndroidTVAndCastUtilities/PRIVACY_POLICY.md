# Privacy Policy for Android Utility Hub

**Effective Date:** September 21, 2026  
**Last Updated:** September 21, 2026

This Privacy Policy describes how **Android Utility Hub** ("we", "us", or "our") handles your information when you use our Android TV and Projector utility application.

---

### 1. Information Collection & Usage

#### A. Local Device Configuration (On-Device Only)
* **HDMI Port Selection & Auto-Switch Settings**: The application saves your selected HDMI input source preference locally on your device using Android `SharedPreferences`.
* **Zero Personal Data Collection by Developer**: We do not collect, store, transmit, or sell your personal details, browsing history, or device files to any private servers. All auto-switch logic executes entirely offline on your device.

#### B. Advertising (Google AdMob)
Our application integrates the **Google Mobile Ads SDK (AdMob)** to display advertisements on the splash screen.
Google AdMob may collect and process certain information in accordance with Google's Privacy Policy, including:
* IP address (for general approximate location)
* Mobile advertising identifiers (e.g., Google Advertising ID)
* Diagnostic, performance, and crash analytics data
* User interaction with advertisements

For more details on how Google uses data from partner apps, please review:  
[How Google uses information from sites or apps that use our services](https://policies.google.com/technologies/partner-sites)

---

### 2. Permissions Used by the App

* `android.permission.RECEIVE_BOOT_COMPLETED`: Required to start the auto-switch detection service upon cold boot.
* `android.permission.INTERNET`: Required by Google AdMob to retrieve and display advertisements.
* `android.permission.ACCESS_NETWORK_STATE`: Used to verify internet availability prior to requesting ads.
* `android.permission.FOREGROUND_SERVICE`: Required on modern Android TV versions to maintain background switching reliability.

---

### 3. Children's Privacy
Our application does not knowingly collect personal identifiable information from children under the age of 13.

---

### 4. Changes to This Privacy Policy
We may update this Privacy Policy from time to time. Any changes will be posted within this document with an updated effective date.

---

### 5. Contact Us
If you have questions or suggestions regarding this Privacy Policy, please reach out via your developer contact email registered on the Google Play Store.
