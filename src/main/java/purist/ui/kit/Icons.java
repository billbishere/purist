package purist.ui.kit;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.GeneralPath;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.util.function.Supplier;
import javax.swing.Icon;

/**
 * Line icons drawn with Java2D, so they stay sharp at any scale and take the skin's colour. Each method takes the
 * icon size in px and a colour supplier (read at paint time, so a skin switch recolours them).
 */
public final class Icons {

    private Icons() {
    }

    public static Icon prev(int size, Supplier<Color> c) {
        return new Line(size, c) {
            @Override
            void draw(Graphics2D g, float s) {
                g.draw(new Line2D.Float(s * .25f, s * .22f, s * .25f, s * .78f));
                g.fill(triangle(s * .78f, s * .22f, s * .78f, s * .78f, s * .32f, s * .5f));
            }
        };
    }

    public static Icon play(int size, Supplier<Color> c) {
        return new Line(size, c) {
            @Override
            void draw(Graphics2D g, float s) {
                g.fill(triangle(s * .3f, s * .2f, s * .3f, s * .8f, s * .8f, s * .5f));
            }
        };
    }

    public static Icon pause(int size, Supplier<Color> c) {
        return new Line(size, c) {
            @Override
            void draw(Graphics2D g, float s) {
                g.fill(new Rectangle2D.Float(s * .28f, s * .22f, s * .14f, s * .56f));
                g.fill(new Rectangle2D.Float(s * .58f, s * .22f, s * .14f, s * .56f));
            }
        };
    }

    public static Icon stop(int size, Supplier<Color> c) {
        return new Line(size, c) {
            @Override
            void draw(Graphics2D g, float s) {
                g.fill(new Rectangle2D.Float(s * .27f, s * .27f, s * .46f, s * .46f));
            }
        };
    }

    public static Icon next(int size, Supplier<Color> c) {
        return new Line(size, c) {
            @Override
            void draw(Graphics2D g, float s) {
                g.fill(triangle(s * .22f, s * .22f, s * .22f, s * .78f, s * .68f, s * .5f));
                g.draw(new Line2D.Float(s * .75f, s * .22f, s * .75f, s * .78f));
            }
        };
    }

    /** Open files (the Winamp eject). */
    public static Icon eject(int size, Supplier<Color> c) {
        return new Line(size, c) {
            @Override
            void draw(Graphics2D g, float s) {
                g.fill(triangle(s * .2f, s * .58f, s * .8f, s * .58f, s * .5f, s * .22f));
                g.fill(new Rectangle2D.Float(s * .2f, s * .68f, s * .6f, s * .1f));
            }
        };
    }

    public static Icon shuffle(int size, Supplier<Color> c) {
        return new Line(size, c) {
            @Override
            void draw(Graphics2D g, float s) {
                Path2D p = new Path2D.Float();
                p.moveTo(s * .15f, s * .3f);
                p.curveTo(s * .45f, s * .3f, s * .5f, s * .7f, s * .82f, s * .7f);
                p.moveTo(s * .15f, s * .7f);
                p.curveTo(s * .45f, s * .7f, s * .5f, s * .3f, s * .82f, s * .3f);
                g.draw(p);
                arrowHead(g, s * .82f, s * .3f, s);
                arrowHead(g, s * .82f, s * .7f, s);
            }
        };
    }

    public static Icon repeat(int size, Supplier<Color> c) {
        return new Line(size, c) {
            @Override
            void draw(Graphics2D g, float s) {
                Path2D p = new Path2D.Float();
                p.moveTo(s * .7f, s * .32f);
                p.lineTo(s * .25f, s * .32f);
                p.quadTo(s * .15f, s * .32f, s * .15f, s * .45f);
                p.lineTo(s * .15f, s * .55f);
                p.moveTo(s * .3f, s * .68f);
                p.lineTo(s * .75f, s * .68f);
                p.quadTo(s * .85f, s * .68f, s * .85f, s * .55f);
                p.lineTo(s * .85f, s * .45f);
                g.draw(p);
                arrowHead(g, s * .72f, s * .32f, s);
                GeneralPath left = triangle(s * .28f, s * .6f, s * .28f, s * .76f, s * .18f, s * .68f);
                g.fill(left);
            }
        };
    }

    public static Icon queue(int size, Supplier<Color> c) {
        return new Line(size, c) {
            @Override
            void draw(Graphics2D g, float s) {
                for (float y : new float[] {.3f, .5f, .7f}) {
                    g.draw(new Line2D.Float(s * .2f, s * y, s * .8f, s * y));
                }
            }
        };
    }

    public static Icon settings(int size, Supplier<Color> c) {
        return new Line(size, c) {
            @Override
            void draw(Graphics2D g, float s) {
                g.draw(new Ellipse2D.Float(s * .32f, s * .32f, s * .36f, s * .36f));
                for (int i = 0; i < 8; i++) {
                    double a = Math.PI / 4 * i;
                    float x1 = (float) (s * .5 + Math.cos(a) * s * .26);
                    float y1 = (float) (s * .5 + Math.sin(a) * s * .26);
                    float x2 = (float) (s * .5 + Math.cos(a) * s * .38);
                    float y2 = (float) (s * .5 + Math.sin(a) * s * .38);
                    g.draw(new Line2D.Float(x1, y1, x2, y2));
                }
            }
        };
    }

    private static GeneralPath triangle(float x1, float y1, float x2, float y2, float x3, float y3) {
        GeneralPath p = new GeneralPath();
        p.moveTo(x1, y1);
        p.lineTo(x2, y2);
        p.lineTo(x3, y3);
        p.closePath();
        return p;
    }

    private static void arrowHead(Graphics2D g, float x, float y, float s) {
        g.fill(triangle(x - s * .02f, y - s * .09f, x - s * .02f, y + s * .09f, x + s * .1f, y));
    }

    /** An icon that strokes or fills shapes on a {@code size}-px square. */
    private abstract static class Line implements Icon {
        private final int size;
        private final Supplier<Color> color;

        Line(int size, Supplier<Color> color) {
            this.size = size;
            this.color = color;
        }

        abstract void draw(Graphics2D g, float s);

        @Override
        public void paintIcon(Component c, Graphics g0, int x, int y) {
            Graphics2D g = (Graphics2D) g0.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
                g.translate(x, y);
                g.setColor(color.get());
                g.setStroke(new BasicStroke(Math.max(1.4f, size / 12f), BasicStroke.CAP_ROUND,
                        BasicStroke.JOIN_ROUND));
                draw(g, size);
            } finally {
                g.dispose();
            }
        }

        @Override
        public int getIconWidth() {
            return size;
        }

        @Override
        public int getIconHeight() {
            return size;
        }
    }
}
