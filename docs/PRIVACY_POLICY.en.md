# OMNIX Privacy Policy

Version 1.0. Effective date: 14 November 2026. The Russian text is the primary version; this is a faithful translation.

OMNIX is a voice AI assistant for OMNIX earbuds: an Android app plus our server. This document describes what data is actually processed, why, who receives it and how long it is kept. It is written from the product code; if the code differs from what is said here, that is a defect, and you can report it at {{CONTACT_EMAIL}}.

## 1. In short

- Simple everyday requests can be handled on the phone (the "Local" mode): the text of such a request does not leave the phone.
- If you choose "Flash" or "Pro" (or "Auto" sends a request to the cloud), the request text goes to our server and from there to an external model provider.
- Our server does not store the text of requests and answers in its database. Usage records contain only technical numbers: lengths, tokens, time, status.
- We do not sell data and do not use it for advertising. The app contains no advertising or analytics SDKs and no crash-reporting service.
- There is no "delete account" button in the app yet: deletion is done on request through an administrator (section 8).

## 2. What data is processed

### 2.1. AI requests (text)

When a request goes to the cloud, the following is sent to our server:

- the text of your request;
- a few of the latest turns of the conversation (so the answer keeps context);
- a system instruction for the model, descriptions of available tools and, when relevant, up to 800 characters of matching assistant memory entries (for example your name or preferences, if you saved them);
- technical fields: source (voice or chat), the request privacy level, the selected tier (Flash or Pro), a request identifier.

Text read from other apps' screens (screen reading through the accessibility service) is not saved to chat history and is not added to cloud requests; banking, payment, password-manager and one-time-code apps are not read at all.

### 2.2. Speech (audio)

Speech recognition is done on the phone whenever possible (the Android system recognizer or a downloaded offline model). In two cases a short phrase in WAV format (up to 30 seconds) is sent to our server and on to Groq (the Whisper model):

- a language the phone recognizer does not know (primarily Turkmen) when no offline model for it is installed;
- the live interpreter mode (one earbud per speaker).

Our server does not write this audio to disk or to the database: it relays it to Groq and returns text. Exception: if recognition is performed by an Android system service (on most phones, Google), its speech processing follows that service's rules, not OMNIX's, and is outside our control.

Waiting for the wake phrase ("Omni") runs on the phone; the microphone stream itself is not sent to the cloud.

### 2.3. Licence, account and device

- The licence key is stored on the server only as a hash plus a short hint of a few characters. The access token is stored as a hash.
- A device identifier (stored as a hash) binds the token to a phone.
- An account is created when a licence is activated; it holds only an internal identifier, status and dates. Name, phone and e-mail are not requested during normal activation.
- The licence log and the operator action log record the request IP address (up to 64 characters), time, action and internal identifiers.

### 2.4. OMNIX earbuds

- Earbuds Bluetooth address (MAC) and serial number: the server stores them to bind the earbuds to an account and to accept OMNIX earbuds only.
- A hash of the phone number: stored so that a trial period cannot be obtained twice for the same number. The server does not store the number itself.
- Binding and verification dates, status.
- The earbud model registry (name, colour, Bluetooth name patterns) is product data, not personal data.

### 2.5. Payments

Plans are currently paid manually: you create a request in the app (a number like OMX-1234, plan, amount, expiry), pay in the way shown in the app, and an operator manually confirms or rejects the request and may leave a note (payment method, comment, who and when). Bank card numbers and account details are not entered into the app or the server. The database has tables for payment providers (Paddle, Heleket); they are not the main path now, and if enabled, the provider processes payment data under its own policy.

### 2.6. Notes and operator log

Support operators may leave internal notes on an account (up to 2000 characters) and perform actions in the admin panel. Every action is written to an immutable log (who, what, when, old and new value, IP).

### 2.7. Usage accounting

For every cloud request the server writes a technical record: request identifier, client identifier, provider and model, latency, token counts, success or error code, request and response length in characters, time, quota bucket. The record contains no request or response text. Records are used for plan limits, cost accounting and failure diagnostics.

Server logs contain the same technical fields (request identifier, privacy level, block reasons, lengths) without request text; keys and tokens are masked in logs.

## 3. Who receives data

We use external providers as processors to obtain model answers:

- Google (Gemini models): receives the request text and context if the request is routed to Gemini.
- Anthropic (Claude models): receives the request text and context when the Pro tier is selected; it may also be used as a fallback.
- Groq: receives the request text and context if the request is routed to Groq, and speech audio for server-side recognition (Whisper).
- OpenRouter: an intermediary through which a request to a third-party model may be served; receives the request text and context.

Which provider answers a particular request is decided by the server based on availability, cost and load; you choose only the tier (Flash or Pro). For Flash, Gemini, Groq and OpenRouter are possible; for Pro, Claude first, and if it is unavailable another provider may serve the request, in which case it is not counted as Pro. Each provider processes data under its own terms; we do not control how long they keep it.

Sent directly from your phone, bypassing our server:

- Weather tool requests to Open-Meteo (a city name or coordinates);
- web search tool requests to DuckDuckGo and Wikipedia (the search phrase);
- actions you ask for in other apps (a call, a message, maps, etc.): they are carried out by the phone itself.

We do not sell data and do not pass it to advertising networks. The product code has no recipients other than those listed; data may be disclosed when required by law.

## 4. What stays on the device only

- Chat history (the app's local database) and voice history.
- Assistant memory (facts, preferences, procedures) and user automations.
- Confirmation settings and the trusted contacts list.
- Keys and access tokens (in the app's protected storage).
- The "Local" mode model and offline speech recognition models.
- Screen content read by the accessibility service and the contacts list you granted access to: they are used on the phone to carry out commands.

Android cloud backup of the app is disabled: history and keys do not enter backups and are not transferred to other devices automatically. You can delete history in the app's "Privacy" section; uninstalling the app erases all local data.

## 5. How cloud requests are protected

- Before sending, a request is classified on the phone and then again on the server. Requests that look like passwords, keys, payment or medical data, government identifiers, private contacts and places are treated as private.
- A request with an undetermined privacy level does not go to the cloud. Private and sensitive requests go only after your explicit consent for that request.
- "Local" mode never contacts the cloud.

The classifier is rule-based and not perfect: it can err in both directions (section 9).

## 6. Retention

- Usage accounting records (no text): 30 days, then deleted automatically.
- Operator sessions and rate-limit records: deleted automatically after expiry.
- Account, licence, earbud binding, trial phone hash, payment requests, licence log and operator log: kept while the account exists and while needed for operation, accounting and abuse protection; there is no automatic deletion period for them yet (section 9).
- Database backups on the server: made daily and kept for 14 days, so data deleted at your request may remain in backups for up to 14 days.
- Data held by external providers (Google, Anthropic, Groq, OpenRouter): under their rules.

## 7. Your rights

You may request:

- access: what account data we hold;
- correction of inaccurate data;
- deletion of the account and related data;
- withdrawal of consent to cloud processing of private requests (simply use the "Local" mode).

Send requests to {{CONTACT_EMAIL}}. Include the licence key hint (last characters) or a request number so we can find the account. We will reply within a reasonable time, normally within 30 days. If you believe we violate your rights, you may contact the data protection authority of your country.

## 8. How data is deleted on request

The product has no automatic deletion endpoint yet. The procedure is: an operator finds the account, revokes the licence and tokens, unbinds the earbuds, then a database administrator deletes the account and related records. Exceptions: data we must keep by law (for example payment records), records in backups (up to 14 days) and at external providers. The operator action log is immutable and is not purged, but it does not contain the text of your requests.

## 9. What we CANNOT guarantee / known limitations

- Audio goes to a recognition server. For Turkmen and the live interpreter mode your phrase is sent to our server and to Groq. Before that, it is impossible to check whether the phrase contains private information: the privacy classifier works on text, not sound.
- The Android system recognizer may send audio to the phone manufacturer's or Google's service; this is outside our control.
- The privacy classifier is rule-based and cannot catch all sensitive data. If you yourself type or say something private and agree to the cloud, it will go to an external provider.
- External providers store and use data under their own terms. We cannot guarantee how long or how they keep it, or that they will not change their terms.
- The app has no delete-account button yet; deletion is manual through a request.
- There is no automatic retention period for the licence and operator logs, payment requests and earbud bindings.
- The phone number hash for the trial period is kept without a time limit to prevent repeat grants; the number cannot be recovered from it directly, but a plain number can be checked by brute force, so treat the hash as personal data.
- Data in transit is protected by connection encryption (HTTPS), but no system is fully secure. We will notify you of security breaches affecting your data by the means available to us.
- The OMNIX earbuds talk to the phone over Bluetooth; the security of that channel is determined by the Bluetooth protocol itself.
- An app installed outside a store (test builds) may self-update and requests the permission to install packages; you confirm each installation yourself.

## 10. Children

The product is not intended for children under 16 and we do not knowingly collect their data. If you believe a child has given us data, write to {{CONTACT_EMAIL}} and we will delete it.

## 11. Android permissions and why they are needed

- Microphone: voice commands and interpreting.
- Internet and network state: communication with the OMNIX server and external tools.
- Bluetooth (connect): working with OMNIX earbuds.
- Notifications and foreground services: the assistant activity indicator, reminders, timers.
- Boot start, WAKE_LOCK, battery optimisation exemption, exact alarms: so the assistant and reminders work in the background.
- Contacts, calls, SMS, phone state: only when you ask to call or send a message; the address book is not uploaded to the server, but a name you say or type in a request is part of the request text and, if the request is a cloud one, goes with it.
- Location: only for location-based tasks (for example "weather where I am"); coordinates go to Open-Meteo.
- Accessibility service: controlling other apps at your request and reading the screen; can be turned off in system settings.
- Screen capture (MediaProjection): a screenshot on your command; handled on the phone.
- Changing system settings (brightness), Do Not Disturb, installing packages (self-update of test builds): as needed; you grant each in system settings yourself.

Any permission can be revoked in Android settings; the related features will stop working.

## 12. Changes to this policy

For material changes we will update the version and date at the top of the document and announce it in the app or on this page. Earlier versions are available on request.

## 13. Contact

For privacy questions and requests for access, correction and deletion, write to {{CONTACT_EMAIL}}.
