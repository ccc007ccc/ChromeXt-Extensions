chrome.runtime.onMessage.addListener((message, _sender, respond) => {
  if (message.type !== "mark-page") return;
  chrome.storage.local.get({ label: "Marked by Page Marker" }).then(({ label }) => {
    let marker = document.getElementById("page-marker-fixture");
    if (!marker) {
      marker = document.createElement("p");
      marker.id = "page-marker-fixture";
      document.body.prepend(marker);
    }
    marker.textContent = label;
    respond({ title: document.title, label });
  }).catch((error) => respond({ error: error.message }));
  return true;
});
