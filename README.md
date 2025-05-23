# 🧠 Collaborative Editor Backend

Welcome to the **Collaborative Editor Backend** — the powerful engine behind real-time document editing, syncing user edits instantly using **WebSockets**, **Java Spring Boot**, and **MongoDB**.

> 🖥️ This is the **Backend (API + WebSocket)** of the project. Find the frontend here: [Collaborative Editor UI](https://github.com/thughari/Collaborative-Editor-UI)

---

## 🌍 Live API

> 🚀 Backend deployed on Render (https://collabeditor-bsua.onrender.com)

---

## 🛠️ Tech Stack

**Backend:**

* ☕ Java 17
* ⚙️ Spring Boot
* 🔄 Spring WebSocket (`TextWebSocketHandler`)
* 🧠 Jackson (for JSON parsing)
* 💾 MongoDB (document persistence)
* 🌐 Deployed via [Render](https://render.com)

---

## 💡 Features

* 🔄 Real-time WebSocket connections for collaborative editing
* 🔗 Join sessions with unique document URLs
* 👥 Multi-user document editing support
* 💬 Commenting system
* 🧠 Username-based session tracking
* ❌ Handles unexpected disconnects gracefully
* 🛑 Smart validation and error handling

---

## 📁 Project Structure

```
com.thughari.collabeditor
├── websocket/
│   └── EditorWebSocketHandler.java  // Handles all WebSocket communication
├── service/
│   └── WebSocketService.java        // Session management, message handling
├── model/                           // Document model
└── CollabEditorApplication.java     // Main Spring Boot application
```

---

## 🚀 Getting Started

### 1. Clone the Repository

```bash
git clone https://github.com/thughari/CollabEditor.git
cd CollabEditor
```

### 2. Configure MongoDB

Make sure MongoDB is running locally and update the connection URI in `src/main/resources/application.properties`:

```properties
spring.data.mongodb.uri=mongodb://localhost:27017/collabeditor
```

### 3. Run the Backend

```bash
./mvnw spring-boot:run
```

> 💡 Default port is `8080`.

---

## 🔌 WebSocket Endpoint

Connect via WebSocket to:

```
ws://localhost:8080/ws/editor/{documentId}
```

Example with Angular:

```typescript
new WebSocket('ws://localhost:8080/ws/editor/abc123');
```

---

## 📬 Message Format

### Join

```json
{
  "type": "join",
  "payload": {
    "documentId": "abc123",
    "username": "Hari"
  }
}
```

### Edit

```json
{
  "type": "edit",
  "payload": {
    "documentId": "abc123",
    "content": "Hello world!"
  }
}
```

### Comment

```json
{
  "type": "comment",
  "payload": {
    "documentId": "abc123",
    "comment": {
      "text": "Add a heading here",
      "position": 5
    }
  }
}
```

---

## ✅ Handling Disconnects

Sessions are automatically cleaned up when:

* WebSocket is closed
* Invalid message or document ID
* Network disconnect

---

## 🧪 Testing the Flow

1. Start MongoDB locally
2. Run backend with `./mvnw spring-boot:run`
3. Launch frontend at `http://localhost:4200`
4. Open in multiple tabs and watch edits sync in real time!

---

## 🤝 Contributing

We welcome improvements to the backend logic or performance! To contribute:

```bash
git checkout -b fix/session-bug
git commit -m "Fix session handling bug"
git push origin fix/session-bug
```

Open a Pull Request ✅

---

## 👨‍💻 Developed by

**Hari Thatikonda**
📫 [LinkedIn](https://linkedin.com/in/hari-thatikonda) | [GitHub](https://github.com/thughari)

---

## 📝 License

MIT — use it freely, improve it collaboratively.
