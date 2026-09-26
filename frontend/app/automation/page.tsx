import { redirect } from "next/navigation";

/** Tab Automations dibuka di halaman pertamanya, Proses. */
export default function Automations() {
  redirect("/automation/processes");
}
