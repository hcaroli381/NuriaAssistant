# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Architecture

This is a JavaFX desktop application designed to run on a Raspberry Pi 3 with a 1024x600 touchscreen. 
The system operates as an "always-on" smart assistant (similar to an Echo Show) with the following components:

1.  **Main JavaFX Application (The Orchestrator):** Handles UI, clock, weather widgets, and the Gemini chat view. Designed for 24/7 uptime.
2.  **Embedded WebView:** Used for the Gemini Web interface. Note: This is resource-intensive on Raspberry Pi 3; always focus on memory optimization and lazy-loading of this component.
3.  **Backend Bridge (Notification System):** An external lightweight service (running on the Pi) that receives push notifications from an external terminal and communicates with the JavaFX app via local sockets/events.

## Build and Run

- **Build:** Use standard Maven commands.
  ```bash
  ./mvnw clean package
  ```
- **Run:** Use the JavaFX plugin to launch the application.
  ```bash
  ./mvnw javafx:run
  ```

## Coding Guidelines

- **Language:** All code, comments, and documentation must be written in English.
- **Always-On Persistence:** 
    - The application must be designed for 24/7 reliability.
    - Implement a "Screensaver" mode (e.g., minimal clock or ambient background) when idle instead of turning off the screen.
- **Touchscreen First:** All UI elements must be large, touch-friendly, and simple. Avoid small text or complex menus.
- **Resource Constraints (Raspberry Pi 3):** 
    - Prioritize RAM and CPU efficiency to maintain long-term stability.
    - Avoid memory leaks in the JavaFX lifecycle.
    - Always verify performance on the target Pi hardware.
- **Communication:** Security is key for the notification system. Use simple, token-authenticated endpoints for receiving messages from your terminal.

## Key Files
- `src/main/java/com/example/nuriaassistant/HelloApplication.java`: Main entry point.
- `src/main/resources/hello-view.fxml`: Main UI layout.
