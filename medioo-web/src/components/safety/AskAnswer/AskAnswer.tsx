import type { AskMedicineJson } from "@/api/types";
import { Crop, type CropWords } from "@/components/Crop/Crop";
import { MessageBubble } from "@/components/MessageBubble/MessageBubble";
import "./AskAnswer.css";

/**
 * Medioo's answer to a question (plan CHAT-web, DR-50), drawn the same way by the composer as it arrives and by the
 * thread once it is stored: first the crop of each prescription line the answer quotes (`medicines`, review of PR 166
 * finding 5), large and before the words, as MedicinesAsWritten draws a line, so the family checks the transcription
 * against the paper's own image of it (DR-12, DR-16); then Java's `answer` as text in Medioo's received bubble (React
 * escapes it; never markup), the bubble holding that text and nothing else; and under it the tel:999 link only when
 * Java says `call_999` (F7: a Bangladeshi phone only). `call999` is the link's words (call999Words) and `crop` the
 * crop's words (`health.crop.alt`, `crop.retry`), both resolved by the server parent: this ships no copy. No "use
 * client": the thread renders it on the server, so a stored answer reads without JavaScript; the composer imports it
 * into its island.
 */
export function AskAnswer({
  answer,
  call999,
  medicines,
  crop,
}: {
  answer: string;
  call999: string | null;
  medicines: readonly AskMedicineJson[];
  crop: CropWords;
}) {
  return (
    <div className="ask-answer" data-ask-answer>
      {medicines.length > 0 && (
        <ul className="ask-answer__crops">
          {medicines.map((m) => (
            <li key={m.crop.src} className="ask-answer__crop-line">
              <Crop src={m.crop.src} width={null} height={null} documentId={m.document_id} words={crop} className="ask-answer__crop" />
            </li>
          ))}
        </ul>
      )}
      <MessageBubble surface="recv">{answer}</MessageBubble>
      {call999 !== null && (
        <a className="ask-answer__call" href="tel:999">
          {call999}
        </a>
      )}
    </div>
  );
}
