# DEC AI screen-control commands

DEC's Accessibility Service is an optional, user-enabled Android feature. It works only after the user enables DEC AI under Android Settings > Accessibility.

## Voice commands

- "Open accessibility settings" — opens Android's Accessibility settings.
- "Read screen" — reads text exposed by the current app's accessibility tree.
- "Scroll down" / "Scroll up" — scrolls a visible scrollable area.
- "Tap [visible label]" — asks for confirmation, then taps a matching visible control.
- "Type [exact text]" — asks for confirmation, then enters text into the focused editable field.
- "Go back" — invokes Android Back.

## Safety and limitations

- Tapping and typing require DEC's existing spoken yes/no confirmation.
- Password fields and fields whose hints or resource IDs indicate password, passcode, PIN, OTP, verification code, CVV or security code are blocked from text entry.
- DEC does not bypass Android permissions, secure windows, app restrictions, or authentication.
- Some apps expose little or no accessibility text; DEC may not be able to read or operate those screens.
- Screen content is read locally by the Android accessibility service. The screen reader action currently returns visible text to DEC's conversation; users should avoid asking DEC to read private screens in public.
- Accessibility must be explicitly enabled by the device owner. Disable it at any time in Android Settings > Accessibility.
