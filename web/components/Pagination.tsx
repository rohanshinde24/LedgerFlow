import Link from "next/link";

export function Pagination({
  basePath,
  query,
  page,
  totalPages
}: {
  basePath: string;
  query: Record<string, string | undefined>;
  page: number;
  totalPages: number;
}) {
  if (totalPages <= 1) {
    return null;
  }

  const href = (target: number) => {
    const params = new URLSearchParams();
    for (const [key, value] of Object.entries(query)) {
      if (value) {
        params.set(key, value);
      }
    }
    params.set("page", String(target));
    return `${basePath}?${params}`;
  };

  return (
    <div className="filters">
      {page > 0 ? (
        <Link className="filter-link" href={href(page - 1)}>
          Previous
        </Link>
      ) : null}
      <span className="subtle">
        Page {page + 1} of {totalPages}
      </span>
      {page + 1 < totalPages ? (
        <Link className="filter-link" href={href(page + 1)}>
          Next
        </Link>
      ) : null}
    </div>
  );
}
