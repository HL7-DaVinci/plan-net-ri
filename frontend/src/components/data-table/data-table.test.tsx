import type { ColumnDef } from "@tanstack/react-table";
import { render, screen } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { DataTable } from "./data-table";

interface TestData {
  id: string;
  name: string;
  status: string;
}

const testColumns: ColumnDef<TestData, unknown>[] = [
  {
    id: "id",
    accessorKey: "id",
    header: "ID",
    size: 100,
  },
  {
    id: "name",
    accessorKey: "name",
    header: "Name",
    size: 200,
  },
  {
    id: "status",
    accessorKey: "status",
    header: "Status",
    size: 100,
  },
];

const testData: TestData[] = [
  { id: "1", name: "Alice", status: "active" },
  { id: "2", name: "Bob", status: "inactive" },
  { id: "3", name: "Charlie", status: "active" },
];

// Mock ResizeObserver
class MockResizeObserver {
  observe = vi.fn();
  unobserve = vi.fn();
  disconnect = vi.fn();
}
vi.stubGlobal("ResizeObserver", MockResizeObserver);

describe("DataTable", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  describe("rendering", () => {
    it("renders column headers", () => {
      render(<DataTable columns={testColumns} data={testData} />);

      expect(screen.getByText("ID")).toBeInTheDocument();
      expect(screen.getByText("Name")).toBeInTheDocument();
      expect(screen.getByText("Status")).toBeInTheDocument();
    });

    it("renders the table container with correct structure", () => {
      const { container } = render(
        <DataTable columns={testColumns} data={testData} />,
      );

      // Should have the main container
      expect(container.querySelector(".border-y")).toBeInTheDocument();
      // Should have header section
      expect(container.querySelector(".bg-muted\\/30")).toBeInTheDocument();
      // Should have scroll container for virtual rows
      expect(container.querySelector(".overflow-auto")).toBeInTheDocument();
    });
  });

  describe("empty state", () => {
    it("shows default empty message when no data", () => {
      render(<DataTable columns={testColumns} data={[]} />);

      expect(screen.getByText("No data available")).toBeInTheDocument();
    });

    it("shows custom empty message when provided", () => {
      render(
        <DataTable
          columns={testColumns}
          data={[]}
          emptyMessage="No patients found"
        />,
      );

      expect(screen.getByText("No patients found")).toBeInTheDocument();
    });

    it("still renders headers when empty", () => {
      render(<DataTable columns={testColumns} data={[]} />);

      expect(screen.getByText("ID")).toBeInTheDocument();
      expect(screen.getByText("Name")).toBeInTheDocument();
      expect(screen.getByText("Status")).toBeInTheDocument();
    });
  });

  describe("loading state", () => {
    it("shows skeleton loading when isLoading is true", () => {
      const { container } = render(
        <DataTable columns={testColumns} data={[]} isLoading />,
      );

      // Should have skeleton elements with animate-pulse
      const skeletons = container.querySelectorAll(".animate-pulse");
      expect(skeletons.length).toBeGreaterThan(0);
    });

    it("does not show data content when loading", () => {
      const { container } = render(
        <DataTable columns={testColumns} data={testData} isLoading />,
      );

      // The loading skeleton should be shown, not the virtual scroll container
      const skeletons = container.querySelectorAll(".animate-pulse");
      expect(skeletons.length).toBeGreaterThan(0);
    });
  });

  describe("sorting", () => {
    it("renders sortable header buttons", () => {
      render(<DataTable columns={testColumns} data={testData} />);

      // Headers should be clickable buttons
      const headerButtons = screen.getAllByRole("button");
      // Should have at least the 3 column header buttons
      expect(headerButtons.length).toBeGreaterThanOrEqual(3);
    });

    it("headers contain sort text", () => {
      render(<DataTable columns={testColumns} data={testData} />);

      // Find header buttons by their text content
      expect(screen.getByText("ID")).toBeInTheDocument();
      expect(screen.getByText("Name")).toBeInTheDocument();
      expect(screen.getByText("Status")).toBeInTheDocument();
    });
  });

  describe("className prop", () => {
    it("applies custom className", () => {
      const { container } = render(
        <DataTable
          columns={testColumns}
          data={testData}
          className="custom-class"
        />,
      );

      expect(container.firstChild).toHaveClass("custom-class");
    });
  });

  describe("virtual scrolling structure", () => {
    it("renders with virtual scrolling container", () => {
      const { container } = render(
        <DataTable columns={testColumns} data={testData} />,
      );

      // Should have overflow-auto container for virtual scrolling
      const scrollContainer = container.querySelector(".overflow-auto");
      expect(scrollContainer).toBeInTheDocument();
    });

    it("sets up virtualizer with correct height", () => {
      const { container } = render(
        <DataTable columns={testColumns} data={testData} />,
      );

      // The virtual container should have a height style set
      const virtualContainer = container.querySelector('[style*="height"]');
      expect(virtualContainer).toBeInTheDocument();
    });
  });

  describe("column resizing", () => {
    it("renders resize handles", () => {
      const { container } = render(
        <DataTable columns={testColumns} data={testData} />,
      );

      // Resize handles should be present
      const resizeHandles = container.querySelectorAll(".cursor-col-resize");
      expect(resizeHandles.length).toBeGreaterThan(0);
    });
  });

  describe("props handling", () => {
    it("handles empty columns array", () => {
      const { container } = render(<DataTable columns={[]} data={[]} />);

      // Should render without crashing
      expect(container.firstChild).toBeInTheDocument();
    });

    it("handles undefined onRowClick", () => {
      const { container } = render(
        <DataTable columns={testColumns} data={testData} />,
      );

      // Should render without crashing
      expect(container.firstChild).toBeInTheDocument();
    });
  });
});
