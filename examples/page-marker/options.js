chrome.storage.local.get({ label: "Marked by Page Marker" }).then(({ label }) => {
  document.getElementById("label").value = label;
});
document.getElementById("save").addEventListener("click", async () => {
  try {
    await chrome.storage.local.set({ label: document.getElementById("label").value });
    document.getElementById("status").textContent = "Saved";
  } catch (error) {
    document.getElementById("status").textContent = error.message;
  }
});
