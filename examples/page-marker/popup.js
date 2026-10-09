document.getElementById("mark").addEventListener("click", async () => {
  const output = document.getElementById("result");
  try {
    const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
    const response = await chrome.tabs.sendMessage(tab.id, { type: "mark-page" });
    output.textContent = response.error || response.title + ": " + response.label;
  } catch (error) {
    output.textContent = error.message;
  }
});
