const { onDocumentCreated } = require("firebase-functions/v2/firestore");
const { initializeApp } = require("firebase-admin/app");
const { getFirestore } = require("firebase-admin/firestore");
const { getMessaging } = require("firebase-admin/messaging");

initializeApp();

exports.notifyNewMessage = onDocumentCreated(
  "rooms/{roomId}/messages/{messageId}",
  async (event) => {
    const snap = event.data;
    if (!snap) return;

    const message = snap.data();
    const roomId = event.params.roomId;
    const senderId = message.senderId;
    const senderName = message.senderName || "새 메시지";
    const content = message.content || "";

    const db = getFirestore();

    // 방 정보 읽기
    const roomSnap = await db.collection("rooms").doc(roomId).get();
    if (!roomSnap.exists) return;
    const room = roomSnap.data();
    const participantUids = room.participantUids || [];
    const roomName = room.name || "채팅방";

    // 보낸 사람을 제외한 멤버들
    const recipients = participantUids.filter((uid) => uid !== senderId);
    if (recipients.length === 0) return;

    // 각 멤버의 fcmTokens 수집
    const tokens = [];
    for (const uid of recipients) {
      const userSnap = await db.collection("users").doc(uid).get();
      if (!userSnap.exists) continue;
      const userTokens = userSnap.data().fcmTokens || [];
      tokens.push(...userTokens);
    }
    if (tokens.length === 0) return;

    // 푸시 발송
    const payload = {
      notification: {
        title: roomName,
        body: `${senderName}: ${content}`,
      },
      data: {
        roomId: roomId,
      },
      android: {
        notification: {
          channelId: "morimi_chat",
        },
      },
      tokens: tokens,
    };

    const response = await getMessaging().sendEachForMulticast(payload);
    console.log(`푸시 발송: 성공 ${response.successCount} / 실패 ${response.failureCount}`);
  }
);