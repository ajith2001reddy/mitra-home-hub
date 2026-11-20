# MITRA Brain

MITRA Brain is a Lenovo Duet-only AI layer built into the native MITRA Home app. It reuses the encrypted Home Assistant connection already in the app; it does not create another hub, alter Docker, or copy the Home Assistant token.

## First-time setup on the Lenovo Duet

1. Build and install the native MITRA Home debug app. The optional helper copies it to `native-android/build/mitra-home-hub-debug.apk`.
2. Open **MITRA Settings → AI Assistant**.
3. Select an OpenAI model available to your API project, or keep `gpt-5-mini`.
4. Tap **Connect OpenAI** and enter the API key in the Android dialog. The key is encrypted with Android Keystore and is never put in the dashboard, Home Assistant, Gmail, or this source tree.
5. Optionally enable web search, conversation history, long-term memory, and smart-home permission.

## Scope and privacy

- Brain chat, user memories, chat history, API-key configuration, and activity logs are available only in the Duet-sized native hub screen.
- Browser and phone control views do not receive the MITRA Brain native bridge or display the chat panel.
- Gmail remains a direct MITRA integration. Its message data is not sent to OpenAI by MITRA Brain.
- The only smart-home actions exposed to MITRA are current registered Home Assistant entities in the supported domains: lights, switches, fans, input booleans, media players, covers, and locks.
- Covers and locks always pause for an explicit confirmation. Every device action refreshes Home Assistant state and is reported as successful only when the requested state is verified.

## Functional checks after setup

Use these tests with a non-critical light or plug first:

1. Ask: `What devices are available?`
2. Ask: `What is the state of <exact device name>?`
3. Ask MITRA to turn the test device on, then off. Confirm the dashboard and physical device update.
4. If Home Assistant Areas are configured, ask: `Turn off the Bedroom.`
5. Ask a current-information question with Web search enabled and verify the source links.
6. Turn off Wi-Fi temporarily and verify MITRA gives a connection error without claiming an action succeeded.
7. Restart the MITRA app. The encrypted Home Assistant connection, AI configuration, local history, and user-approved memory should remain available.

Do not test locks, covers, or other sensitive devices until you intentionally confirm the action prompt.
