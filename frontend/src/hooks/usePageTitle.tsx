import { useEffect } from "react";

export const usePageTitle = (title: string) => {
  useEffect(() => {
    document.title = "Unisync | " + title;
  }, [title]);
};
