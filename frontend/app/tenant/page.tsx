import { redirect } from "next/navigation";

/** Konteks Tenant dibuka di halaman pertamanya, Folder — seperti Orchestrator. */
export default function Tenant() {
  redirect("/tenant/folders");
}
