/*
 * Copyright (C) 2021 Eric Medvet <eric.medvet@gmail.com> (as Eric Medvet <eric.medvet@gmail.com>)
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package it.units.erallab.hmsrobots.viewers;

import it.units.erallab.hmsrobots.core.objects.immutable.Snapshot;
import it.units.erallab.hmsrobots.tasks.Task;
import it.units.erallab.hmsrobots.util.BoundingBox;
import it.units.erallab.hmsrobots.util.Grid;
import it.units.erallab.hmsrobots.util.Point2;
import it.units.erallab.hmsrobots.viewers.drawers.SensorReading;
import org.apache.commons.lang3.time.StopWatch;
import org.apache.commons.lang3.tuple.Pair;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.Flushable;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * @author Eric Medvet <eric.medvet@gmail.com>
 */
public class GridFileWriter implements Flushable, GridSnapshotListener {

  private final int w;
  private final int h;
  private final double startTime;
  private final double frameRate;
  private final VideoUtils.EncoderFacility encoder;
  private final File file;

  private final Grid<String> namesGrid;
  private final Grid<List<Double>> timesGrid;
  private final Grid<Framer> framerGrid;
  private final List<BufferedImage> images;

  private final GraphicsDrawer graphicsDrawer;

  private static final Logger L = Logger.getLogger(GridFileWriter.class.getName());

  public GridFileWriter(int w, int h, double startTime, double frameRate, VideoUtils.EncoderFacility encoder, File file, Grid<String> namesGrid) throws IOException {
    this(w, h, startTime, frameRate, encoder, file, namesGrid, GraphicsDrawer.build());
  }

  public GridFileWriter(int w, int h, double startTime, double frameRate, VideoUtils.EncoderFacility encoder, File file, Grid<String> namesGrid, GraphicsDrawer graphicsDrawer) throws IOException {
    this.w = w;
    this.h = h;
    this.startTime = startTime;
    this.namesGrid = namesGrid;
    this.frameRate = frameRate;
    this.encoder = encoder;
    this.file = file;
    images = new ArrayList<>();
    this.graphicsDrawer = graphicsDrawer;
    framerGrid = Grid.create(namesGrid.getW(), namesGrid.getH(), (x, y) -> new RobotFollower((int) frameRate * 3, 1.5d, 100, RobotFollower.AggregateType.MAX));
    timesGrid = Grid.create(namesGrid.getW(), namesGrid.getH(), (x, y) -> new ArrayList<>());
  }

  @Override
  public SnapshotListener listener(final int lX, final int lY) {
    return (Snapshot snapshot) -> {
      List<Double> times = timesGrid.get(lX, lY);
      double lastTime = times.isEmpty() ? Double.NEGATIVE_INFINITY : times.get(times.size() - 1);
      if (snapshot.getTime() >= startTime && snapshot.getTime() - lastTime >= 1d / frameRate) {
        int frameNumber = (int) Math.round((snapshot.getTime() - startTime) * frameRate);
        int lastFrameNumber = times.isEmpty() ? frameNumber : (int) Math.round((times.get(times.size() - 1) - startTime) * frameRate);
        synchronized (images) {
          times.add(snapshot.getTime());
          while (frameNumber >= images.size()) {
            images.add(new BufferedImage(w, h, BufferedImage.TYPE_3BYTE_BGR));
          }
          for (int i = lastFrameNumber; i <= frameNumber; i++) {
            BufferedImage image = images.get(i);
            Graphics2D g = image.createGraphics();
            double localW = (double) w / (double) namesGrid.getW();
            double localH = (double) h / (double) namesGrid.getH();
            //obtain viewport
            BoundingBox frame = framerGrid.get(lX, lY).getFrame(snapshot, localW / localH);
            //draw
            graphicsDrawer.draw(
                snapshot, g,
                BoundingBox.build(
                    Point2.build(localW * lX, localH * lY),
                    Point2.build(localW * (lX + 1), localH * (lY + 1))
                ),
                frame, namesGrid.get(lX, lY)
            );
            g.dispose();
          }
        }
      }
    };
  }


  @Override
  public void flush() throws IOException {
    L.fine(String.format("Saving video on %s", file));
    StopWatch stopWatch = StopWatch.createStarted();
    VideoUtils.encodeAndSave(images, frameRate, file, encoder);
    long millis = stopWatch.getTime(TimeUnit.MILLISECONDS);
    L.fine(String.format(
        "Video saved: %.1fMB written in %.2fs",
        Files.size(file.toPath()) / 1024f / 1024f,
        millis / 1000f
    ));
  }

  public static <S> void save(Task<S, ?> task, Grid<Pair<String, S>> namedSolutions, int w, int h, double startTime, double frameRate, VideoUtils.EncoderFacility encoder, File file) throws IOException {
    ExecutorService executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
    GridFileWriter gridFileWriter = new GridFileWriter(
        w, h, startTime, frameRate, encoder, file,
        Grid.create(namedSolutions, p -> p == null ? null : p.getLeft()),
        GraphicsDrawer.build().setConfigurable("drawers", List.of(
            it.units.erallab.hmsrobots.viewers.drawers.Ground.build(),
            it.units.erallab.hmsrobots.viewers.drawers.Robot.build(),
            it.units.erallab.hmsrobots.viewers.drawers.Voxel.build(),
            SensorReading.build(),
            it.units.erallab.hmsrobots.viewers.drawers.Lidar.build(),
            it.units.erallab.hmsrobots.viewers.drawers.Angle.build()
        ))
    );
    GridEpisodeRunner<S> runner = new GridEpisodeRunner<>(
        namedSolutions,
        task,
        gridFileWriter,
        executor
    );
    runner.run();
    executor.shutdownNow();
  }

  public static <S> void save(Task<S, ?> task, List<S> ss, int w, int h, double startTime, double frameRate, VideoUtils.EncoderFacility encoder, File file) throws IOException {
    int nRows = (int) Math.ceil(Math.sqrt(ss.size()));
    int nCols = (int) Math.ceil((double) ss.size() / (double) nRows);
    Grid<Pair<String, S>> namedSolutions = Grid.create(nRows, nCols);
    for (int i = 0; i < ss.size(); i++) {
      namedSolutions.set(i % nRows, Math.floorDiv(i, nRows), Pair.of(Integer.toString(i), ss.get(i)));
    }
    save(task, namedSolutions, w, h, startTime, frameRate, encoder, file);
  }

  public static <S> void save(Task<S, ?> task, S s, int w, int h, double startTime, double frameRate, VideoUtils.EncoderFacility encoder, File file) throws IOException {
    save(task, Grid.create(1, 1, Pair.of("solution", s)), w, h, startTime, frameRate, encoder, file);
  }

}
