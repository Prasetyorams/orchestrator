import { redirect } from "next/navigation";

/** Tab Pemantauan dibuka di halaman pertamanya, Robot. */
export default function Pemantauan() {
  redirect("/monitoring/robots");
}
