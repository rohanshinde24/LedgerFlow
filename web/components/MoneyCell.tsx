import { formatMoney, isNegative } from "@/lib/format";
import type { MoneyView } from "@/lib/types";

export function MoneyCell({ money }: { money: MoneyView }) {
  return <td className={isNegative(money) ? "numeric negative" : "numeric"}>{formatMoney(money)}</td>;
}
