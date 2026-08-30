// Order Service base URL. The browser calls Order Service directly;
// Order Service is the one that talks to Payment Service.
const ORDER_SERVICE_URL = "http://localhost:8082/orders";

const createOrderBtn = document.getElementById("createOrderBtn");
const orderIdInput = document.getElementById("orderId");

const resultCard = document.getElementById("resultCard");
const resultBanner = document.getElementById("resultBanner");
const resOrderId = document.getElementById("resOrderId");
const resPaymentStatus = document.getElementById("resPaymentStatus");
const resResponseTime = document.getElementById("resResponseTime");
const resHttpStatus = document.getElementById("resHttpStatus");
const resMechanism = document.getElementById("resMechanism");
const resAttempts = document.getElementById("resAttempts");

createOrderBtn.addEventListener("click", createOrder);

async function createOrder() {
  const orderId = orderIdInput.value.trim();
  const paymentType = document.querySelector('input[name="paymentType"]:checked').value;

  if (!orderId) {
    alert("Please enter an Order ID");
    return;
  }

  createOrderBtn.disabled = true;
  createOrderBtn.textContent = "Creating Order...";

  try {
    const response = await fetch(ORDER_SERVICE_URL, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ orderId, paymentType }),
    });

    const data = await response.json();
    showResult(data, response.ok);
  } catch (error) {
    showError(orderId, "Could not reach Order Service. Is it running on port 8082?");
  } finally {
    createOrderBtn.disabled = false;
    createOrderBtn.textContent = "Create Order";
  }
}

function showResult(data, httpOk) {
  resultCard.hidden = false;

  const isSuccess = httpOk && data.paymentStatus === "SUCCESS";
  const isFallback = data.paymentStatus === "FALLBACK";

  const icon = isSuccess ? "✓" : isFallback ? "⚠" : "✗";
  const bannerClass = isSuccess ? "success" : isFallback ? "fallback" : "error";

  resultBanner.textContent = `${icon} ${data.message}`;
  resultBanner.className = "banner " + bannerClass;

  resOrderId.textContent = data.orderId ?? "—";
  resPaymentStatus.textContent = data.paymentStatus ?? "—";
  resResponseTime.textContent = data.responseTimeMs != null ? `${data.responseTimeMs} ms` : "—";
  resHttpStatus.textContent = data.paymentHttpStatus != null ? data.paymentHttpStatus : "—";
  resMechanism.textContent = data.mechanism ?? "—";
  resAttempts.textContent = data.attempts != null ? data.attempts : "—";
}

function showError(orderId, message) {
  resultCard.hidden = false;

  resultBanner.textContent = `✗ ${message}`;
  resultBanner.className = "banner error";

  resOrderId.textContent = orderId || "—";
  resPaymentStatus.textContent = "—";
  resResponseTime.textContent = "—";
  resHttpStatus.textContent = "—";
  resMechanism.textContent = "—";
  resAttempts.textContent = "—";
}
