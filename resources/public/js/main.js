const ws = new WebSocket("ws://localhost:3000/ws");
const messages = document.getElementById("messages");
const input = document.getElementById("message-input");

ws.onopen = () => {
    console.log("Connected to WebSocket");
};

ws.onmessage = (event) => {
    const msg = JSON.parse(event.data);
    const div = document.createElement("div");
    div.className = `message ${msg.from}`;
    div.textContent = `${msg.from}: ${msg.text}`;
    messages.appendChild(div);
    messages.scrollTop = messages.scrollHeight;
};

ws.onclose = () => {
    console.log("WebSocket closed");
};

function sendMessage() {
    const text = input.value.trim();
    if (text) {
        ws.send(JSON.stringify({ from: "michael", text: text }));
        input.value = "";
    }
}

input.addEventListener("keypress", (e) => {
    if (e.key === "Enter") {
        sendMessage();
    }
});