package net.querz.mcaselector.tiles;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import net.querz.mcaselector.util.Helper;
import net.querz.mcaselector.util.Point2f;
import net.querz.mcaselector.util.Point2i;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public class TileMap extends Canvas {

	private float scale = 1;	//higher --> +    lower -->  -

	public static final float MAX_SCALE = 5;
	public static final float MIN_SCALE = 0.2f;
	public static final float CHUNK_GRID_SCALE = 1.5f; //show chunk grid if scale is larger than this
	public static final int TILE_VISIBILITY_THRESHOLD = 2;

	private GraphicsContext context;

	private Point2f offset = new Point2f();

	private Point2f previousMouseLocation = null;
	private Point2f firstMouseLocation = null;

	private Map<Point2i, Tile> tiles = new HashMap<>();
	private Set<Tile> visibleTiles = ConcurrentHashMap.newKeySet();

	private int selectedChunks = 0;
	private Point2i hoveredBlock = null;

	private boolean showChunkGrid = true;
	private boolean showRegionGrid = true;

	private Consumer<TileMap> updateListener;
	private Consumer<TileMap> hoverListener;

	private QueuedRegionImageGenerator qrig;

	public TileMap(int width, int height) {
		super(width, height);
		context = getGraphicsContext2D();
		this.setOnMousePressed(this::onMousePressed);
		this.setOnMouseReleased(e -> onMouseReleased());
		this.setOnMouseDragged(this::onMouseDragged);
		this.setOnScroll(this::onScroll);
		this.setOnMouseMoved(this::onMouseMoved);
		this.setOnMouseExited(e -> onMouseExited());
		qrig = new QueuedRegionImageGenerator(QueuedRegionImageGenerator.PROCESSOR_COUNT, this);
		update();
	}

	private void onMouseMoved(MouseEvent event) {
		hoveredBlock = getMouseBlock(event.getX(), event.getY());
		hoverListener.accept(this);
	}

	private void onMouseExited() {
		hoveredBlock = null;
	}

	private void onScroll(ScrollEvent event) {
		float oldScale = scale;
		scale -= event.getDeltaY() / 100;
		scale = scale < MAX_SCALE ? (scale > MIN_SCALE ? scale : MIN_SCALE) : MAX_SCALE;

		//calculate the difference between the old max and the new max point
		Point2f diff = offset.add((float) getWidth() * oldScale, (float) getHeight() * oldScale)
				.sub(offset.add((float) getWidth() * scale, (float) getHeight() * scale));

		offset = offset.add(diff.div(2));
		update();
	}

	private void onMousePressed(MouseEvent event) {
		firstMouseLocation = new Point2f(event.getX(), event.getY());

		switch (event.getButton()) {
			case PRIMARY:
				mark(event.getX(), event.getY(), true);
				break;
			case SECONDARY:
				mark(event.getX(), event.getY(), false);
				break;
		}
		update();
	}

	private void onMouseReleased() {
		previousMouseLocation = null;
	}

	private void onMouseDragged(MouseEvent event) {
		switch (event.getButton()) {
			case MIDDLE:
				Point2f mouseLocation = new Point2f(event.getX(), event.getY());
				if (previousMouseLocation != null) {
					Point2f diff = mouseLocation.sub(previousMouseLocation);
					diff = diff.mul(-1);
					offset = offset.add(diff.mul(scale));
				}
				previousMouseLocation = mouseLocation;
				break;
			case PRIMARY:
				mark(event.getX(), event.getY(), true);
				break;
			case SECONDARY:
				mark(event.getX(), event.getY(), false);
				break;
		}
		update();
	}

	public void update() {
		if (updateListener != null) {
			updateListener.accept(this);
		}
		qrig.validateJobs();
		for (Tile tile : visibleTiles) {
			if (!tile.isVisible(this, TILE_VISIBILITY_THRESHOLD)) {
				visibleTiles.remove(tile);
				tile.unload();
				if (!tile.isMarked() && tile.getMarkedChunks().size() == 0) {
					tiles.remove(tile.getLocation());
				}
			}
		}
		draw(context);
	}

	public void setOnUpdate(Consumer<TileMap> listener) {
		updateListener = listener;
	}

	public void setOnHover(Consumer<TileMap> listener) {
		hoverListener = listener;
	}

	public Point2f getOffset() {
		return offset;
	}

	public float getScale() {
		return scale;
	}

	public void setShowRegionGrid(boolean showRegionGrid) {
		this.showRegionGrid = showRegionGrid;
		update();
	}

	public void setShowChunkGrid(boolean showChunkGrid) {
		this.showChunkGrid = showChunkGrid;
		update();
	}

	public void goTo(int x, int z) {
		offset = new Point2f(x + getWidth() / 2, z + getHeight() / 2);
		update();
	}

	public int getSelectedChunks() {
		return selectedChunks;
	}

	public Point2i getHoveredBlock() {
		return hoveredBlock;
	}

	public List<Point2i> getVisibleRegions() {
		List<Point2i> regions = new ArrayList<>();
		runOnVisibleRegions(r -> regions.add(Helper.blockToRegion(r)));
		return regions;
	}

	public void clear() {
		tiles.clear();
		selectedChunks = 0;
	}

	public void clearSelection() {
		for (Map.Entry<Point2i, Tile> entry : tiles.entrySet()) {
			entry.getValue().clearMarks();
		}
		selectedChunks = 0;
		update();
	}

	//will return a map of all chunks marked for deletion, mapped to regions.
	//if an entire region is marked for deletion, the value in the map will be null.
	//keys are region coordinates
	//values are chunk coordinates
	public Map<Point2i, Set<Point2i>> getMarkedChunks() {
		Map<Point2i, Set<Point2i>> chunks = new HashMap<>();

		for (Map.Entry<Point2i, Tile> entry : tiles.entrySet()) {
			if (entry.getValue().isMarked()) {
				chunks.put(Helper.blockToRegion(entry.getKey()), null);
				continue;
			}
			Set<Point2i> markedChunks = entry.getValue().getMarkedChunks();
			if (markedChunks.size() == 0) {
				continue;
			}
			Set<Point2i> markedChunksList = new HashSet<>(markedChunks.size());
			markedChunks.forEach(c -> markedChunksList.add(Helper.blockToChunk(c)));
			chunks.put(Helper.blockToRegion(entry.getKey()), markedChunksList);
		}
		return chunks;
	}

	private Point2i getMouseBlock(double x, double z) {
		int blockX = (int) (offset.getX() + x * scale);
		int blockZ = (int) (offset.getY() + z * scale);
		return new Point2i(blockX, blockZ);
	}

	private Point2i getMouseRegionBlock(double x, double z) {
		return Helper.regionToBlock(Helper.blockToRegion(getMouseBlock(x, z)));
	}

	private Point2i getMouseChunkBlock(double x, double z) {
		return Helper.chunkToBlock(Helper.blockToChunk(getMouseBlock(x, z)));
	}

	private void sortPoints(Point2i a, Point2i b) {
		Point2i aa = a.clone();
		a.setX(a.getX() < b.getX() ? a.getX() : b.getX());
		a.setY(a.getY() < b.getY() ? a.getY() : b.getY());
		b.setX(aa.getX() < b.getX() ? b.getX() : aa.getX());
		b.setY(aa.getY() < b.getY() ? b.getY() : aa.getY());
	}

	private void mark(double mouseX, double mouseY, boolean marked) {
		if (scale > CHUNK_GRID_SCALE) {
			Point2i regionBlock = getMouseRegionBlock(mouseX, mouseY);
			Point2i firstRegionBlock = getMouseRegionBlock(firstMouseLocation.getX(), firstMouseLocation.getY());
			sortPoints(firstRegionBlock, regionBlock);
			for (int x = firstRegionBlock.getX(); x <= regionBlock.getX(); x += Tile.SIZE) {
				for (int z = firstRegionBlock.getY(); z <= regionBlock.getY(); z += Tile.SIZE) {
					Tile tile = tiles.get(new Point2i(x, z));
					if (tile != null && !tile.isEmpty()) {
						if (tile.isMarked() && !marked) {
							selectedChunks -= 1024;
						} else if (!tile.isMarked() && marked) {
							selectedChunks += 1024 - tile.getMarkedChunks().size();
						}
						tile.mark(marked);
					}
				}
			}
		} else {
			Point2i chunkBlock = getMouseChunkBlock(mouseX, mouseY);
			Point2i firstChunkBlock = getMouseChunkBlock(firstMouseLocation.getX(), firstMouseLocation.getY());
			sortPoints(firstChunkBlock, chunkBlock);
			for (int x = firstChunkBlock.getX(); x <= chunkBlock.getX(); x += Tile.CHUNK_SIZE) {
				for (int z = firstChunkBlock.getY(); z <= chunkBlock.getY(); z += Tile.CHUNK_SIZE) {
					Point2i chunk = new Point2i(x, z);
					Tile tile = tiles.get(Helper.regionToBlock(Helper.blockToRegion(chunk)));
					if (tile != null) {
						if (tile.isMarked(chunk) && !marked && !tile.isEmpty()) {
							selectedChunks--;
							tile.unMark(chunk);
						} else if (!tile.isMarked(chunk) && marked && !tile.isEmpty()) {
							selectedChunks++;
							tile.mark(chunk);
						}
					}
				}
			}
		}
	}

	private void draw(GraphicsContext ctx) {
		runOnVisibleRegions(region -> {
			if (!tiles.containsKey(region)) {
				tiles.put(region, new Tile(region));
			}
			Tile tile = tiles.get(region);
			visibleTiles.add(tile);

			Point2i regionOffset = region.sub((int) offset.getX(), (int) offset.getY());

			if (!tile.isLoaded() && !tile.isLoading()) {
				tile.loadFromCache();
				if (!tile.isLoaded()) {
					qrig.addJob(tile);
				}
			}
			tile.draw(ctx, scale, new Point2f(regionOffset.getX() / scale, regionOffset.getY() / scale), showRegionGrid, showChunkGrid);
		});
	}

	private void runOnVisibleRegions(Consumer<Point2i> consumer) {
		//regionLocation is the south-west-most visible region in the window
		Point2i regionLocation = Helper.regionToBlock(Helper.blockToRegion(offset.toPoint2i()));

		//get all tiles that are visible inside the window
		for (int x = regionLocation.getX(); x < offset.getX() + getWidth() * scale; x += Tile.SIZE) {
			for (int z = regionLocation.getY(); z < offset.getY() + getHeight() * scale; z += Tile.SIZE) {
				consumer.accept(new Point2i(x, z));
			}
		}
	}

	@Override
	public void resize(double width, double height) {
		setWidth(width);
		setHeight(height);
		update();
	}

	@Override
	public boolean isResizable() {
		return true;
	}

	@Override
	public double minHeight(double width) {
		return 0;
	}

	@Override
	public double minWidth(double height) {
		return 0;
	}

	@Override
	public double maxHeight(double width) {
		return Integer.MAX_VALUE;
	}

	@Override
	public double maxWidth(double height) {
		return Integer.MAX_VALUE;
	}
}