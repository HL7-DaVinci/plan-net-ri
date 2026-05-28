import { Link } from "@tanstack/react-router";
import { FileQuestion, Home, Search } from "lucide-react";
import { Button } from "@/components/ui/button";

export function NotFoundComponent() {
  return (
    <div className="flex flex-col items-center justify-center min-h-[60vh] px-4">
      {/* Decorative background */}
      <div className="absolute inset-0 overflow-hidden pointer-events-none">
        <div className="absolute top-1/4 left-1/4 w-64 h-64 bg-primary/5 rounded-full blur-3xl" />
        <div className="absolute bottom-1/4 right-1/4 w-96 h-96 bg-primary/3 rounded-full blur-3xl" />
      </div>

      {/* Content */}
      <div className="relative flex flex-col items-center text-center space-y-6">
        {/* Icon with decorative ring */}
        <div className="relative">
          <div className="absolute inset-0 bg-primary/10 rounded-full blur-xl scale-150" />
          <div className="relative flex h-24 w-24 items-center justify-center rounded-full bg-muted/50 border border-border">
            <FileQuestion className="h-12 w-12 text-muted-foreground" />
          </div>
        </div>

        {/* Error code with gradient */}
        <h1 className="text-7xl font-bold bg-gradient-to-b from-foreground to-foreground/50 bg-clip-text text-transparent">
          404
        </h1>

        {/* Description */}
        <div className="space-y-2 max-w-md">
          <h2 className="text-xl font-semibold">Page not found</h2>
          <p className="text-muted-foreground">
            The page you're looking for doesn't exist or has been moved. Check
            the URL or navigate back to explore available resources.
          </p>
        </div>

        {/* Actions */}
        <div className="flex flex-col sm:flex-row gap-3 pt-4">
          <Button asChild>
            <Link to="/">
              <Home className="h-4 w-4 mr-2" />
              Back to Dashboard
            </Link>
          </Button>
          <Button variant="outline" asChild>
            <Link to="/resources">
              <Search className="h-4 w-4 mr-2" />
              Browse Resources
            </Link>
          </Button>
        </div>
      </div>
    </div>
  );
}
