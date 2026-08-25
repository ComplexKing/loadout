package dev.loadout.launcher.ui;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import javax.swing.JPanel;
import javax.swing.Timer;

/**
 * A rounded panel that responds to the pointer.
 *
 * <p>The hover is animated rather than switched. A card that snaps between two colours
 * reads as a state change; one that eases over about a tenth of a second reads as
 * something responding to you, and that difference is most of what separates an interface
 * that feels built from one that feels generated.
 *
 * <p>The shadow is painted rather than borrowed from the platform, because Swing has no
 * shadow of its own and a flat card on a flat background has nothing to sit on.
 */
public class Card extends JPanel {
	private static final int FRAME_MS = 16;      // ~60fps
	private static final float STEP = 0.14f;     // reaches full hover in ~7 frames

	private final Timer animator;
	private float hover;
	private float target;
	private boolean clickable;
	private Runnable onClick;

	private Color background = Theme.SURFACE;
	private Color hoverBackground = Theme.SURFACE_HOVER;
	private boolean drawShadow = true;
	private boolean selected;

	public Card() {
		setOpaque(false);
		this.animator = new Timer(FRAME_MS, event -> step());

		addMouseListener(new MouseAdapter() {
			@Override
			public void mouseEntered(MouseEvent e) {
				animateTo(1f);
			}

			@Override
			public void mouseExited(MouseEvent e) {
				animateTo(0f);
			}

			@Override
			public void mouseClicked(MouseEvent e) {
				if (Card.this.clickable && Card.this.onClick != null) {
					Card.this.onClick.run();
				}
			}
		});
	}

	public Card onClick(Runnable action) {
		this.onClick = action;
		this.clickable = action != null;
		setCursor(Cursor.getPredefinedCursor(this.clickable ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
		return this;
	}

	public Card colors(Color normal, Color hovered) {
		this.background = normal;
		this.hoverBackground = hovered;
		repaint();
		return this;
	}

	public Card shadow(boolean enabled) {
		this.drawShadow = enabled;
		repaint();
		return this;
	}

	public void setSelected(boolean value) {
		if (this.selected != value) {
			this.selected = value;
			repaint();
		}
	}

	public boolean isSelected() {
		return this.selected;
	}

	private void animateTo(float value) {
		this.target = value;
		if (!this.animator.isRunning()) {
			this.animator.start();
		}
	}

	private void step() {
		if (Math.abs(this.hover - this.target) < 0.01f) {
			this.hover = this.target;
			this.animator.stop();
		} else {
			this.hover += (this.target - this.hover) * (STEP * 3f);
		}
		repaint();
	}

	@Override
	protected void paintComponent(Graphics graphics) {
		Graphics2D g = (Graphics2D) graphics.create();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

		int width = getWidth();
		int height = getHeight();
		int inset = this.drawShadow ? 3 : 0;

		if (this.drawShadow) {
			// Three widening, fading rectangles. Cheap, and enough to lift the card off
			// the background without looking like a drop-shadow filter from 2006.
			for (int i = inset; i > 0; i--) {
				g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.05f));
				g.setColor(Color.BLACK);
				g.fill(new RoundRectangle2D.Float(i, i + 1, width - i * 2f, height - i * 2f,
						Theme.RADIUS, Theme.RADIUS));
			}
			g.setComposite(AlphaComposite.SrcOver);
		}

		g.setColor(blend(this.background, this.hoverBackground, this.hover));
		g.fill(new RoundRectangle2D.Float(inset, inset, width - inset * 2f, height - inset * 2f,
				Theme.RADIUS, Theme.RADIUS));

		// Selection reads as an accent edge rather than a fill, so the card's own colour
		// still communicates hover independently.
		if (this.selected) {
			g.setColor(Theme.ACCENT);
			g.setStroke(new java.awt.BasicStroke(1.6f));
			g.draw(new RoundRectangle2D.Float(inset + 0.8f, inset + 0.8f,
					width - inset * 2f - 1.6f, height - inset * 2f - 1.6f, Theme.RADIUS, Theme.RADIUS));
		} else if (this.hover > 0.01f) {
			g.setColor(withAlpha(Theme.LINE, (int) (140 * this.hover)));
			g.draw(new RoundRectangle2D.Float(inset + 0.5f, inset + 0.5f,
					width - inset * 2f - 1f, height - inset * 2f - 1f, Theme.RADIUS, Theme.RADIUS));
		}

		g.dispose();
		super.paintComponent(graphics);
	}

	static Color blend(Color from, Color to, float amount) {
		float t = Math.max(0f, Math.min(1f, amount));
		return new Color(
				Math.round(from.getRed() + (to.getRed() - from.getRed()) * t),
				Math.round(from.getGreen() + (to.getGreen() - from.getGreen()) * t),
				Math.round(from.getBlue() + (to.getBlue() - from.getBlue()) * t));
	}

	static Color withAlpha(Color color, int alpha) {
		return new Color(color.getRed(), color.getGreen(), color.getBlue(),
				Math.max(0, Math.min(255, alpha)));
	}
}
