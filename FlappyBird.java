import javax.sound.sampled.*;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.prefs.Preferences;

public class
FlappyBird extends JPanel implements ActionListener, KeyListener, MouseListener {

    // --- Window and Canvas Dimensions ---
    public static final int WIDTH = 400;
    public static final int HEIGHT = 640;
    public static final int GROUND_HEIGHT = 110;
    public static final int GROUND_Y = HEIGHT - GROUND_HEIGHT;

    // --- Physics Constants ---
    public static final double GRAVITY = 0.42;
    public static final double JUMP_VELOCITY = -7.5;
    public static final double MAX_FALL_SPEED = 9.5;
    public static final int PIPE_SPEED = 3;
    public static final int PIPE_WIDTH = 68;
    public static final int PIPE_GAP = 145;
    public static final int PIPE_SPAWN_INTERVAL = 90; // frames between spawns (~1.5s)

    // --- Game States ---
    public enum GameState {
        START, PLAYING, GAME_OVER
    }

    private GameState state = GameState.START;

    // --- Game Entities & State ---
    private final Bird bird;
    private final List<Pipe> pipes = new ArrayList<>();
    private final List<Cloud> clouds = new ArrayList<>();
    private int pipeSpawnTimer = 0;
    private int score = 0;
    private int highScore = 0;
    private boolean isNewHighScore = false;
    private double groundOffset = 0;
    private int shakeFrames = 0;
    private boolean soundEnabled = true;

    // --- Autopilot / Automation State ---
    private boolean autoPlay = true;
    private int autoStartTimer = 0;
    private int autoRestartTimer = 0;

    private final Timer gameLoopTimer;
    private final Random random = new Random();
    private final Preferences prefs = Preferences.userNodeForPackage(FlappyBird.class);

    // --- Sound Synthesizer ---
    private final SoundSystem soundSystem = new SoundSystem();

    public FlappyBird() {
        setPreferredSize(new Dimension(WIDTH, HEIGHT));
        setFocusable(true);
        addKeyListener(this);
        addMouseListener(this);

        // Load saved high score
        highScore = prefs.getInt("flappy_high_score", 0);

        bird = new Bird(80, HEIGHT / 2 - 30);

        // Initialize drifting background clouds
        clouds.add(new Cloud(50, 80, 0.4, 1.1));
        clouds.add(new Cloud(220, 140, 0.6, 0.85));
        clouds.add(new Cloud(340, 60, 0.3, 1.2));
        clouds.add(new Cloud(120, 200, 0.5, 0.95));

        // 60 FPS Game Loop (~16.6 ms per tick)
        gameLoopTimer = new Timer(1000 / 60, this);
        gameLoopTimer.start();
    }

    private void restartGame() {
        bird.reset(80, HEIGHT / 2 - 30);
        pipes.clear();
        pipeSpawnTimer = 0;
        score = 0;
        isNewHighScore = false;
        shakeFrames = 0;
        autoStartTimer = 0;
        autoRestartTimer = 0;
        state = GameState.PLAYING;
        bird.jump();
        if (soundEnabled) soundSystem.playJump();
    }

    private void triggerGameOver() {
        state = GameState.GAME_OVER;
        shakeFrames = 10; // Trigger screen shake
        if (score > highScore) {
            highScore = score;
            isNewHighScore = true;
            prefs.putInt("flappy_high_score", highScore);
        }
        if (soundEnabled) soundSystem.playHit();
    }

    private void handleJump() {
        if (state == GameState.START) {
            restartGame();
        } else if (state == GameState.PLAYING) {
            if (!autoPlay) {
                bird.jump();
                if (soundEnabled) soundSystem.playJump();
            }
        } else if (state == GameState.GAME_OVER) {
            // Allow restart if shake has finished
            if (shakeFrames <= 0) {
                restartGame();
            }
        }
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        // Update clouds (drifting in background always)
        for (Cloud cloud : clouds) {
            cloud.update();
        }

        if (state == GameState.START) {
            // Idle bobbing animation
            bird.idleBob();
            groundOffset = (groundOffset + PIPE_SPEED * 0.7) % 24;

            // Auto-start if autopilot is enabled
            if (autoPlay) {
                autoStartTimer++;
                if (autoStartTimer >= 90) { // ~1.5s
                    restartGame();
                }
            }
        } else if (state == GameState.PLAYING) {
            // Autonomous jump decision before physics update
            if (autoPlay && decideAutoJump()) {
                bird.jump();
                if (soundEnabled) soundSystem.playJump();
            }

            // Physics update
            bird.update();
            groundOffset = (groundOffset + PIPE_SPEED) % 24;

            // Check collision with ground or ceiling
            if (bird.getY() + bird.getHeight() >= GROUND_Y) {
                bird.setY(GROUND_Y - bird.getHeight());
                triggerGameOver();
            } else if (bird.getY() <= 0) {
                bird.setY(0);
                bird.setVelocity(0);
            }

            // Pipe spawning
            pipeSpawnTimer++;
            if (pipeSpawnTimer >= PIPE_SPAWN_INTERVAL) {
                pipeSpawnTimer = 0;
                spawnPipe();
            }

            // Update pipes and detect collisions
            Iterator<Pipe> iterator = pipes.iterator();
            while (iterator.hasNext()) {
                Pipe pipe = iterator.next();
                pipe.update();

                // Collision detection
                if (pipe.collidesWith(bird)) {
                    triggerGameOver();
                    break;
                }

                // Scoring: bird passes pipe center
                if (!pipe.isPassed() && pipe.getX() + PIPE_WIDTH < bird.getX()) {
                    pipe.setPassed(true);
                    score++;
                    if (soundEnabled) soundSystem.playScore();
                }

                // Remove off-screen pipes
                if (pipe.isOffScreen()) {
                    iterator.remove();
                }
            }
        } else if (state == GameState.GAME_OVER) {
            // Bird drops to ground if not already there
            if (bird.getY() + bird.getHeight() < GROUND_Y) {
                bird.update();
                if (bird.getY() + bird.getHeight() >= GROUND_Y) {
                    bird.setY(GROUND_Y - bird.getHeight());
                }
            }
            if (shakeFrames > 0) {
                shakeFrames--;
            }

            // Auto-restart if autopilot is enabled
            if (autoPlay && shakeFrames <= 0) {
                autoRestartTimer++;
                if (autoRestartTimer >= 90) { // ~1.5s
                    restartGame();
                }
            }
        }

        repaint();
    }

    private void spawnPipe() {
        int minPipeHeight = 50;
        int maxPipeHeight = GROUND_Y - PIPE_GAP - minPipeHeight;
        int topHeight = minPipeHeight + random.nextInt(Math.max(1, maxPipeHeight - minPipeHeight));
        pipes.add(new Pipe(WIDTH, topHeight, PIPE_GAP));
    }

    /**
     * Autonomous decision engine for Flappy Bird.
     * Accurately simulates trajectories and executes precision jumps
     * to clear all pipes continuously without user input.
     */
    private boolean decideAutoJump() {
        Pipe targetPipe = null;
        for (Pipe p : pipes) {
            // Check upcoming pipe whose right edge hasn't passed the bird
            if (p.getX() + PIPE_WIDTH >= bird.getX() + 4) {
                targetPipe = p;
                break;
            }
        }

        double birdY = bird.getY();
        double velocity = bird.getVelocity();

        double targetY = (targetPipe != null)
                ? targetPipe.getTopHeight() + (targetPipe.getGap() / 2.0) - (bird.getHeight() / 2.0)
                : (GROUND_Y / 2.0) - (bird.getHeight() / 2.0);

        // 1. Safety check: Would jumping now cause a collision with top pipe or ceiling?
        double simY = birdY;
        double simV = JUMP_VELOCITY;
        boolean jumpHitsTop = false;
        for (int step = 0; step < 25; step++) {
            simV = Math.min(simV + GRAVITY, MAX_FALL_SPEED);
            simY += simV;
            if (simY < 0) {
                jumpHitsTop = true;
                break;
            }
            if (targetPipe != null) {
                double nextPipeX = targetPipe.getX() - (step + 1) * PIPE_SPEED;
                if (bird.getX() + bird.getWidth() - 4 > nextPipeX && bird.getX() + 4 < nextPipeX + PIPE_WIDTH) {
                    if (simY + 3 < targetPipe.getTopHeight()) {
                        jumpHitsTop = true;
                        break;
                    }
                }
            }
        }

        if (jumpHitsTop) {
            return false;
        }

        // 2. Recovery check: If we fall for 1 frame, can we still recover safely without hitting bottom pipe or ground?
        double fallY = birdY;
        double fallV = Math.min(velocity + GRAVITY, MAX_FALL_SPEED);
        fallY += fallV;

        double nextV = JUMP_VELOCITY;
        double nextY = fallY;
        boolean nextJumpHitsBottom = false;
        for (int step = 1; step <= 25; step++) {
            nextV = Math.min(nextV + GRAVITY, MAX_FALL_SPEED);
            nextY += nextV;
            if (nextY + bird.getHeight() >= GROUND_Y) {
                nextJumpHitsBottom = true;
                break;
            }
            if (targetPipe != null) {
                double nextPipeX = targetPipe.getX() - (step + 1) * PIPE_SPEED;
                if (bird.getX() + bird.getWidth() - 4 > nextPipeX && bird.getX() + 4 < nextPipeX + PIPE_WIDTH) {
                    if (nextY + bird.getHeight() - 3 > targetPipe.getTopHeight() + targetPipe.getGap()) {
                        nextJumpHitsBottom = true;
                        break;
                    }
                }
            }
        }

        if (nextJumpHitsBottom) {
            return true;
        }

        // 3. Trajectory alignment: Jump if below target gap altitude and descending or near peak
        if (birdY > targetY && velocity >= -0.5) {
            return true;
        }

        return false;
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();

        // Enable high-quality antialiasing
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

        // Apply screen shake on game over impact
        if (shakeFrames > 0) {
            int offsetX = random.nextInt(7) - 3;
            int offsetY = random.nextInt(7) - 3;
            g2.translate(offsetX, offsetY);
        }

        // 1. Draw Sky Background
        drawSky(g2);

        // 2. Draw Background Clouds
        for (Cloud cloud : clouds) {
            cloud.draw(g2);
        }

        // 3. Draw Cityscape / Hills silhouette
        drawCityscape(g2);

        // 4. Draw Pipes
        for (Pipe pipe : pipes) {
            pipe.draw(g2);
        }

        // 5. Draw Ground
        drawGround(g2);

        // 6. Draw Bird
        bird.draw(g2);

        // 7. Draw UI overlays according to State
        if (state == GameState.START) {
            drawStartScreen(g2);
        } else if (state == GameState.PLAYING) {
            drawScoreHUD(g2);
        } else if (state == GameState.GAME_OVER) {
            drawGameOverScreen(g2);
        }

        // 8. Sound Icon Indicator in top right
        drawSoundIndicator(g2);

        // 9. Auto-Pilot Status HUD in top left
        drawAutoPilotHUD(g2);

        g2.dispose();
    }

    // --- Visual Rendering Helpers ---

    private void drawSky(Graphics2D g2) {
        GradientPaint skyGradient = new GradientPaint(
                0, 0, new Color(112, 197, 206),
                0, GROUND_Y, new Color(215, 243, 245)
        );
        g2.setPaint(skyGradient);
        g2.fillRect(0, 0, WIDTH, GROUND_Y);
    }

    private void drawCityscape(Graphics2D g2) {
        // Distant hills
        g2.setColor(new Color(155, 218, 142, 160));
        g2.fillOval(-40, GROUND_Y - 70, 200, 100);
        g2.fillOval(120, GROUND_Y - 85, 240, 120);
        g2.fillOval(300, GROUND_Y - 65, 180, 90);

        // City skyline silhouettes
        g2.setColor(new Color(190, 222, 185, 180));
        int[] buildingHeights = {45, 60, 35, 75, 50, 40, 65, 55, 70, 45, 60};
        int bw = 38;
        for (int i = 0; i < buildingHeights.length; i++) {
            int bh = buildingHeights[i];
            int bx = i * 36;
            g2.fillRect(bx, GROUND_Y - bh, bw, bh);
            // Little decorative windows
            g2.setColor(new Color(230, 245, 225, 160));
            for (int wy = GROUND_Y - bh + 6; wy < GROUND_Y - 10; wy += 12) {
                g2.fillRect(bx + 8, wy, 6, 6);
                g2.fillRect(bx + 22, wy, 6, 6);
            }
            g2.setColor(new Color(190, 222, 185, 180));
        }
    }

    private void drawGround(Graphics2D g2) {
        // Main dirt background
        g2.setColor(new Color(222, 216, 149));
        g2.fillRect(0, GROUND_Y, WIDTH, GROUND_HEIGHT);

        // Moving diagonal dirt stripes
        g2.setColor(new Color(210, 198, 128));
        int stripeWidth = 14;
        int stripeSpacing = 24;
        for (int x = -(int) groundOffset - stripeSpacing; x < WIDTH + stripeSpacing; x += stripeSpacing) {
            int[] xs = {x, x + stripeWidth, x, x - stripeWidth};
            int[] ys = {GROUND_Y + 16, GROUND_Y + 16, HEIGHT, HEIGHT};
            g2.fillPolygon(xs, ys, 4);
        }

        // Top grass layer
        g2.setColor(new Color(115, 200, 56));
        g2.fillRect(0, GROUND_Y, WIDTH, 14);

        // Dark green border lines
        g2.setColor(new Color(85, 160, 38));
        g2.fillRect(0, GROUND_Y + 14, WIDTH, 3);
        g2.setColor(new Color(60, 120, 25));
        g2.drawRect(0, GROUND_Y, WIDTH, 1);
    }

    private void drawScoreHUD(Graphics2D g2) {
        String scoreText = String.valueOf(score);
        Font font = new Font("Arial", Font.BOLD, 48);
        g2.setFont(font);

        FontMetrics fm = g2.getFontMetrics();
        int textX = (WIDTH - fm.stringWidth(scoreText)) / 2;
        int textY = 90;

        // Shadow outline
        g2.setColor(new Color(30, 30, 30));
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                if (dx != 0 || dy != 0) {
                    g2.drawString(scoreText, textX + dx, textY + dy);
                }
            }
        }
        // White text
        g2.setColor(Color.WHITE);
        g2.drawString(scoreText, textX, textY);
    }

    private void drawStartScreen(Graphics2D g2) {
        // Title banner
        String title = "FLAPPY BIRD";
        Font titleFont = new Font("Arial", Font.BOLD, 42);
        g2.setFont(titleFont);
        FontMetrics fm = g2.getFontMetrics();
        int titleX = (WIDTH - fm.stringWidth(title)) / 2;
        int titleY = 170;

        // Title 3D shadow
        g2.setColor(new Color(40, 40, 40));
        g2.drawString(title, titleX + 3, titleY + 3);
        g2.setColor(new Color(255, 150, 0));
        g2.drawString(title, titleX + 1, titleY + 1);
        g2.setColor(Color.WHITE);
        g2.drawString(title, titleX, titleY);

        // Subtitle instructions (blinking)
        long blink = (System.currentTimeMillis() / 450) % 2;
        if (blink == 0) {
            String sub = autoPlay ? "AUTO-PILOT ACTIVE" : "PRESS SPACE OR CLICK";
            Font subFont = new Font("Arial", Font.BOLD, 18);
            g2.setFont(subFont);
            FontMetrics sfm = g2.getFontMetrics();
            int subX = (WIDTH - sfm.stringWidth(sub)) / 2;
            int subY = 390;

            g2.setColor(new Color(30, 30, 30));
            g2.drawString(sub, subX + 1, subY + 1);
            g2.setColor(autoPlay ? new Color(110, 255, 140) : new Color(255, 230, 100));
            g2.drawString(sub, subX, subY);
        }

        if (autoPlay) {
            String autoHint = "Starting automatically... or press Space";
            Font ahFont = new Font("Arial", Font.BOLD, 12);
            g2.setFont(ahFont);
            FontMetrics ahfm = g2.getFontMetrics();
            int ahX = (WIDTH - ahfm.stringWidth(autoHint)) / 2;
            g2.setColor(new Color(40, 40, 40));
            g2.drawString(autoHint, ahX + 1, 416);
            g2.setColor(Color.WHITE);
            g2.drawString(autoHint, ahX, 415);
        }

        // Display High Score
        if (highScore > 0) {
            String best = "HIGH SCORE: " + highScore;
            Font bFont = new Font("Arial", Font.BOLD, 16);
            g2.setFont(bFont);
            FontMetrics bfm = g2.getFontMetrics();
            int bx = (WIDTH - bfm.stringWidth(best)) / 2;
            g2.setColor(new Color(40, 40, 40));
            g2.drawString(best, bx + 1, 451);
            g2.setColor(Color.WHITE);
            g2.drawString(best, bx, 450);
        }

        // Small key instructions at bottom
        String hint = "[Space: Start]  [A: Auto]  [M: Mute]  [Esc/Q: Quit]";
        Font hFont = new Font("Arial", Font.PLAIN, 12);
        g2.setFont(hFont);
        FontMetrics hfm = g2.getFontMetrics();
        g2.setColor(new Color(60, 60, 60));
        g2.drawString(hint, (WIDTH - hfm.stringWidth(hint)) / 2, GROUND_Y + 50);
    }

    private void drawGameOverScreen(Graphics2D g2) {
        // "GAME OVER" banner
        String banner = "GAME OVER";
        Font bannerFont = new Font("Arial", Font.BOLD, 42);
        g2.setFont(bannerFont);
        FontMetrics bfm = g2.getFontMetrics();
        int bx = (WIDTH - bfm.stringWidth(banner)) / 2;
        int by = 160;

        // Shadow
        g2.setColor(new Color(30, 30, 30));
        g2.drawString(banner, bx + 3, by + 3);
        g2.setColor(new Color(230, 50, 40));
        g2.drawString(banner, bx, by);

        // Score Card Panel
        int panelW = 280;
        int panelH = 170;
        int panelX = (WIDTH - panelW) / 2;
        int panelY = 195;

        // Panel Shadow & Body
        g2.setColor(new Color(0, 0, 0, 80));
        g2.fillRoundRect(panelX + 4, panelY + 4, panelW, panelH, 18, 18);

        g2.setColor(new Color(235, 222, 175));
        g2.fillRoundRect(panelX, panelY, panelW, panelH, 18, 18);

        g2.setColor(new Color(85, 70, 50));
        g2.setStroke(new BasicStroke(3.5f));
        g2.drawRoundRect(panelX, panelY, panelW, panelH, 18, 18);

        // Inner frame
        g2.setStroke(new BasicStroke(1.5f));
        g2.setColor(new Color(215, 200, 150));
        g2.drawRoundRect(panelX + 6, panelY + 6, panelW - 12, panelH - 12, 12, 12);

        // Medal Box
        int medalBoxX = panelX + 22;
        int medalBoxY = panelY + 46;
        int medalBoxSize = 58;
        g2.setColor(new Color(210, 195, 145));
        g2.fillRoundRect(medalBoxX, medalBoxY, medalBoxSize, medalBoxSize, 8, 8);
        g2.setColor(new Color(110, 95, 70));
        g2.drawRoundRect(medalBoxX, medalBoxY, medalBoxSize, medalBoxSize, 8, 8);

        // Label: MEDAL
        Font labelFont = new Font("Arial", Font.BOLD, 13);
        g2.setFont(labelFont);
        g2.setColor(new Color(200, 100, 20));
        g2.drawString("MEDAL", medalBoxX + 6, panelY + 36);

        // Draw Medal if earned
        drawMedal(g2, medalBoxX + medalBoxSize / 2, medalBoxY + medalBoxSize / 2, score);

        // Score Label & Value
        g2.setColor(new Color(200, 100, 20));
        g2.drawString("SCORE", panelX + 130, panelY + 42);

        Font valFont = new Font("Arial", Font.BOLD, 26);
        g2.setFont(valFont);
        g2.setColor(new Color(40, 40, 40));
        g2.drawString(String.valueOf(score), panelX + 130, panelY + 70);

        // High Score Label & Value
        g2.setFont(labelFont);
        g2.setColor(new Color(200, 100, 20));
        g2.drawString("BEST", panelX + 130, panelY + 104);

        g2.setFont(valFont);
        g2.setColor(new Color(40, 40, 40));
        g2.drawString(String.valueOf(highScore), panelX + 130, panelY + 132);

        // "NEW" High Score Badge
        if (isNewHighScore && score > 0) {
            g2.setColor(new Color(230, 40, 30));
            g2.fillRoundRect(panelX + 200, panelY + 95, 46, 20, 6, 6);
            g2.setColor(Color.WHITE);
            Font badgeFont = new Font("Arial", Font.BOLD, 11);
            g2.setFont(badgeFont);
            g2.drawString("NEW!", panelX + 207, panelY + 110);
        }

        // Restart prompt
        long blink = (System.currentTimeMillis() / 450) % 2;
        if (blink == 0) {
            String restart = autoPlay ? "AUTO-RESTARTING..." : "PRESS SPACE TO RESTART";
            Font rFont = new Font("Arial", Font.BOLD, 18);
            g2.setFont(rFont);
            FontMetrics rfm = g2.getFontMetrics();
            int rx = (WIDTH - rfm.stringWidth(restart)) / 2;
            int ry = 420;

            g2.setColor(new Color(30, 30, 30));
            g2.drawString(restart, rx + 1, ry + 1);
            g2.setColor(autoPlay ? new Color(110, 255, 140) : new Color(255, 230, 80));
            g2.drawString(restart, rx, ry);
        }
    }

    private void drawMedal(Graphics2D g2, int cx, int cy, int currentScore) {
        if (currentScore < 10) {
            // No medal
            g2.setColor(new Color(150, 140, 120));
            g2.setFont(new Font("Arial", Font.ITALIC, 11));
            g2.drawString("None", cx - 12, cy + 4);
            return;
        }

        Color mainColor, shineColor, borderColor;
        if (currentScore >= 40) {
            // Platinum
            mainColor = new Color(225, 235, 245);
            shineColor = Color.WHITE;
            borderColor = new Color(120, 140, 160);
        } else if (currentScore >= 30) {
            // Gold
            mainColor = new Color(255, 210, 25);
            shineColor = new Color(255, 245, 160);
            borderColor = new Color(180, 120, 0);
        } else if (currentScore >= 20) {
            // Silver
            mainColor = new Color(210, 215, 220);
            shineColor = new Color(245, 248, 250);
            borderColor = new Color(120, 130, 140);
        } else {
            // Bronze (10+)
            mainColor = new Color(205, 127, 50);
            shineColor = new Color(235, 175, 115);
            borderColor = new Color(130, 75, 25);
        }

        int radius = 18;
        // Outer shadow
        g2.setColor(borderColor);
        g2.fillOval(cx - radius - 1, cy - radius - 1, (radius + 1) * 2, (radius + 1) * 2);

        // Body
        g2.setColor(mainColor);
        g2.fillOval(cx - radius, cy - radius, radius * 2, radius * 2);

        // Inner highlight
        g2.setColor(shineColor);
        g2.fillOval(cx - radius + 3, cy - radius + 3, 9, 9);

        // Star in center
        g2.setColor(borderColor);
        int[] sx = {cx, cx + 3, cx + 9, cx + 4, cx + 6, cx, cx - 6, cx - 4, cx - 9, cx - 3};
        int[] sy = {cy - 9, cy - 3, cy - 3, cy + 1, cy + 8, cy + 4, cy + 8, cy + 1, cy - 3, cy - 3};
        g2.fillPolygon(sx, sy, 10);
    }

    private void drawSoundIndicator(Graphics2D g2) {
        int x = WIDTH - 34;
        int y = 14;
        g2.setColor(new Color(0, 0, 0, 100));
        g2.fillRoundRect(x - 4, y - 4, 28, 24, 6, 6);

        g2.setColor(soundEnabled ? Color.WHITE : new Color(240, 80, 80));
        // Speaker icon
        int[] sx = {x, x + 5, x + 11, x + 11, x + 5, x};
        int[] sy = {y + 5, y + 5, y, y + 16, y + 11, y + 11};
        g2.fillPolygon(sx, sy, 6);

        if (soundEnabled) {
            // Sound wave arcs
            g2.drawArc(x + 10, y + 3, 6, 10, -60, 120);
            g2.drawArc(x + 10, y, 10, 16, -60, 120);
        } else {
            // 'X' for muted
            g2.setStroke(new BasicStroke(2f));
            g2.drawLine(x + 14, y + 4, x + 20, y + 12);
            g2.drawLine(x + 20, y + 4, x + 14, y + 12);
        }
    }

    private void drawAutoPilotHUD(Graphics2D g2) {
        int x = 12;
        int y = 14;
        int w = 138;
        int h = 24;

        // Semi-transparent rounded background
        g2.setColor(new Color(0, 0, 0, 110));
        g2.fillRoundRect(x, y, w, h, 8, 8);
        g2.setColor(autoPlay ? new Color(60, 180, 90, 180) : new Color(140, 140, 140, 140));
        g2.setStroke(new BasicStroke(1.2f));
        g2.drawRoundRect(x, y, w, h, 8, 8);

        // Status indicator dot
        int dotX = x + 8;
        int dotY = y + 7;
        int dotSize = 10;
        if (autoPlay) {
            g2.setColor(new Color(40, 235, 100));
            g2.fillOval(dotX, dotY, dotSize, dotSize);
            g2.setColor(Color.WHITE);
            g2.fillOval(dotX + 2, dotY + 2, 4, 4);
        } else {
            g2.setColor(new Color(150, 150, 150));
            g2.fillOval(dotX, dotY, dotSize, dotSize);
        }

        // Status text
        g2.setFont(new Font("Arial", Font.BOLD, 11));
        g2.setColor(Color.WHITE);
        String label = autoPlay ? "AUTOPILOT: ON [A]" : "AUTOPILOT: OFF [A]";
        g2.drawString(label, dotX + 15, y + 16);
    }

    // --- Key and Mouse Event Handling ---

    @Override
    public void keyPressed(KeyEvent e) {
        int code = e.getKeyCode();
        if (code == KeyEvent.VK_SPACE || code == KeyEvent.VK_UP || code == KeyEvent.VK_W) {
            handleJump();
        } else if (code == KeyEvent.VK_A) {
            autoPlay = !autoPlay;
            repaint();
        } else if (code == KeyEvent.VK_M) {
            soundEnabled = !soundEnabled;
            repaint();
        } else if (code == KeyEvent.VK_R) {
            restartGame();
        } else if (code == KeyEvent.VK_ESCAPE || code == KeyEvent.VK_Q) {
            System.exit(0);
        }
    }

    @Override
    public void mousePressed(MouseEvent e) {
        // Toggle sound if clicked top-right speaker
        if (e.getX() >= WIDTH - 40 && e.getY() <= 40) {
            soundEnabled = !soundEnabled;
            repaint();
            return;
        }
        // Toggle autopilot if clicked top-left badge
        if (e.getX() <= 160 && e.getY() <= 42) {
            autoPlay = !autoPlay;
            repaint();
            return;
        }
        handleJump();
    }

    @Override public void keyReleased(KeyEvent e) {}
    @Override public void keyTyped(KeyEvent e) {}
    @Override public void mouseClicked(MouseEvent e) {}
    @Override public void mouseReleased(MouseEvent e) {}
    @Override public void mouseEntered(MouseEvent e) {}
    @Override public void mouseExited(MouseEvent e) {}

    // =========================================================================
    // --- Nested Entity: Bird ---
    // =========================================================================
    public static class Bird {
        private final double startX;
        private final double startY;
        private double x;
        private double y;
        private double velocity = 0;
        private final int width = 34;
        private final int height = 24;
        private double angle = 0;
        private int wingCycle = 0;

        public Bird(double x, double y) {
            this.startX = x;
            this.startY = y;
            reset(x, y);
        }

        public void reset(double x, double y) {
            this.x = x;
            this.y = y;
            this.velocity = 0;
            this.angle = 0;
            this.wingCycle = 0;
        }

        public void jump() {
            this.velocity = JUMP_VELOCITY;
            this.angle = -0.45; // Tilt upward instantly on jump
            this.wingCycle = 12; // Start flap
        }

        public void idleBob() {
            y = startY + Math.sin(System.currentTimeMillis() * 0.005) * 7;
            angle = 0;
            wingCycle = (wingCycle + 1) % 18;
        }

        public void update() {
            velocity += GRAVITY;
            if (velocity > MAX_FALL_SPEED) {
                velocity = MAX_FALL_SPEED;
            }
            y += velocity;

            // Smooth rotation interpolation
            double targetAngle = Math.toRadians(Math.min(75, Math.max(-25, velocity * 8.5)));
            angle += (targetAngle - angle) * 0.18;

            if (wingCycle > 0) {
                wingCycle--;
            }
        }

        public void draw(Graphics2D g2) {
            AffineTransform oldTransform = g2.getTransform();
            g2.translate(x + width / 2.0, y + height / 2.0);
            g2.rotate(angle);

            int cx = -width / 2;
            int cy = -height / 2;

            // Bird Body (Bright yellow egg shape)
            GradientPaint bodyGrad = new GradientPaint(
                    cx, cy, new Color(255, 225, 40),
                    cx, cy + height, new Color(230, 160, 10)
            );
            g2.setPaint(bodyGrad);
            g2.fillOval(cx, cy, width, height);

            // Body Outline
            g2.setColor(new Color(40, 40, 40));
            g2.setStroke(new BasicStroke(2f));
            g2.drawOval(cx, cy, width, height);

            // Belly / Cheek highlight
            g2.setColor(new Color(255, 245, 140, 200));
            g2.fillOval(cx + 4, cy + 3, 14, 8);

            // Orange Cheek Blush
            g2.setColor(new Color(245, 120, 50, 160));
            g2.fillOval(cx + 12, cy + 11, 8, 7);

            // Big Cartoon Eye
            int eyeX = cx + 18;
            int eyeY = cy + 2;
            int eyeSize = 12;
            g2.setColor(Color.WHITE);
            g2.fillOval(eyeX, eyeY, eyeSize, eyeSize);
            g2.setColor(new Color(40, 40, 40));
            g2.setStroke(new BasicStroke(1.5f));
            g2.drawOval(eyeX, eyeY, eyeSize, eyeSize);

            // Eye Pupil & Specular Sparkle
            g2.setColor(new Color(25, 25, 25));
            g2.fillOval(eyeX + 5, eyeY + 3, 6, 6);
            g2.setColor(Color.WHITE);
            g2.fillOval(eyeX + 6, eyeY + 4, 2, 2);

            // Orange Beak
            g2.setColor(new Color(245, 85, 20));
            int[] bx = {cx + 25, cx + width + 5, cx + 25};
            int[] by = {cy + 9, cy + 14, cy + 19};
            g2.fillPolygon(bx, by, 3);
            g2.setColor(new Color(40, 40, 40));
            g2.drawPolygon(bx, by, 3);
            g2.drawLine(cx + 25, cy + 14, cx + width + 2, cy + 14); // mouth split

            // Flapping Wing
            int wingOffset = (wingCycle > 6) ? -4 : (wingCycle > 0 ? 2 : 0);
            g2.setColor(new Color(255, 245, 130));
            g2.fillOval(cx + 2, cy + 7 + wingOffset, 15, 10);
            g2.setColor(new Color(40, 40, 40));
            g2.drawOval(cx + 2, cy + 7 + wingOffset, 15, 10);

            g2.setTransform(oldTransform);
        }

        // Slightly smaller hitbox for player-friendly fair collision detection
        public Rectangle getHitbox() {
            int padX = 4;
            int padY = 3;
            return new Rectangle((int) x + padX, (int) y + padY, width - padX * 2, height - padY * 2);
        }

        public double getX() { return x; }
        public double getY() { return y; }
        public void setY(double y) { this.y = y; }
        public int getWidth() { return width; }
        public int getHeight() { return height; }
        public void setVelocity(double v) { this.velocity = v; }
        public double getVelocity() { return velocity; }
    }

    // =========================================================================
    // --- Nested Entity: Pipe ---
    // =========================================================================
    public static class Pipe {
        private int x;
        private final int topHeight;
        private final int gap;
        private boolean passed = false;

        public Pipe(int x, int topHeight, int gap) {
            this.x = x;
            this.topHeight = topHeight;
            this.gap = gap;
        }

        public void update() {
            x -= PIPE_SPEED;
        }

        public void draw(Graphics2D g2) {
            int capHeight = 24;
            int capLip = 4;

            // --- Draw Top Pipe ---
            drawPipeCylinder(g2, x, 0, PIPE_WIDTH, topHeight - capHeight);
            // Top pipe cap
            drawPipeCap(g2, x - capLip, topHeight - capHeight, PIPE_WIDTH + capLip * 2, capHeight);

            // --- Draw Bottom Pipe ---
            int bottomY = topHeight + gap;
            int bottomHeight = GROUND_Y - bottomY;
            // Bottom pipe cap
            drawPipeCap(g2, x - capLip, bottomY, PIPE_WIDTH + capLip * 2, capHeight);
            // Bottom pipe cylinder
            drawPipeCylinder(g2, x, bottomY + capHeight, PIPE_WIDTH, bottomHeight - capHeight);
        }

        private void drawPipeCylinder(Graphics2D g2, int px, int py, int pw, int ph) {
            if (ph <= 0) return;

            // 3D Pipe gradient
            GradientPaint pipeGrad = new GradientPaint(
                    px, 0, new Color(130, 215, 60),
                    px + pw, 0, new Color(55, 125, 20)
            );
            g2.setPaint(pipeGrad);
            g2.fillRect(px, py, pw, ph);

            // Left highlight stripe
            g2.setColor(new Color(180, 245, 110, 170));
            g2.fillRect(px + 6, py, 8, ph);

            // Outline
            g2.setColor(new Color(30, 50, 20));
            g2.setStroke(new BasicStroke(2f));
            g2.drawRect(px, py, pw, ph);
        }

        private void drawPipeCap(Graphics2D g2, int cx, int cy, int cw, int ch) {
            // Cap body gradient
            GradientPaint capGrad = new GradientPaint(
                    cx, 0, new Color(140, 225, 65),
                    cx + cw, 0, new Color(50, 115, 18)
            );
            g2.setPaint(capGrad);
            g2.fillRect(cx, cy, cw, ch);

            // Cap highlight
            g2.setColor(new Color(195, 255, 130, 190));
            g2.fillRect(cx + 8, cy, 10, ch);

            // Outline
            g2.setColor(new Color(30, 50, 20));
            g2.setStroke(new BasicStroke(2f));
            g2.drawRect(cx, cy, cw, ch);
        }

        public boolean collidesWith(Bird bird) {
            Rectangle bRect = bird.getHitbox();
            Rectangle topPipeRect = new Rectangle(x, 0, PIPE_WIDTH, topHeight);
            Rectangle bottomPipeRect = new Rectangle(x, topHeight + gap, PIPE_WIDTH, GROUND_Y - (topHeight + gap));

            return bRect.intersects(topPipeRect) || bRect.intersects(bottomPipeRect);
        }

        public boolean isOffScreen() {
            return x + PIPE_WIDTH + 10 < 0;
        }

        public int getX() { return x; }
        public int getTopHeight() { return topHeight; }
        public int getGap() { return gap; }
        public boolean isPassed() { return passed; }
        public void setPassed(boolean p) { this.passed = p; }
    }

    // =========================================================================
    // --- Nested Entity: Cloud ---
    // =========================================================================
    public static class Cloud {
        private double x;
        private final double y;
        private final double speed;
        private final double scale;

        public Cloud(double x, double y, double speed, double scale) {
            this.x = x;
            this.y = y;
            this.speed = speed;
            this.scale = scale;
        }

        public void update() {
            x -= speed;
            if (x < -120) {
                x = WIDTH + 40;
            }
        }

        public void draw(Graphics2D g2) {
            AffineTransform old = g2.getTransform();
            g2.translate(x, y);
            g2.scale(scale, scale);

            // Cloud shadow
            g2.setColor(new Color(200, 225, 235, 140));
            g2.fillOval(0, 8, 60, 28);
            g2.fillOval(18, 0, 42, 32);
            g2.fillOval(42, 6, 38, 28);

            // Cloud body
            g2.setColor(new Color(255, 255, 255, 220));
            g2.fillOval(0, 6, 58, 26);
            g2.fillOval(18, 0, 40, 30);
            g2.fillOval(40, 5, 36, 26);

            g2.setTransform(old);
        }
    }

    // =========================================================================
    // --- Retro 8-bit Procedural Sound System ---
    // =========================================================================
    public static class SoundSystem {
        private final ExecutorService soundPool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            return t;
        });

        private final byte[] jumpSound;
        private final byte[] scoreSound;
        private final byte[] hitSound;

        public SoundSystem() {
            jumpSound = synthesizeJump();
            scoreSound = synthesizeScore();
            hitSound = synthesizeHit();
        }

        public void playJump() { play(jumpSound); }
        public void playScore() { play(scoreSound); }
        public void playHit() { play(hitSound); }

        private void play(byte[] data) {
            if (data == null || data.length == 0) return;
            soundPool.submit(() -> {
                try {
                    AudioFormat format = new AudioFormat(44100, 8, 1, true, false);
                    DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
                    if (!AudioSystem.isLineSupported(info)) return;
                    SourceDataLine line = (SourceDataLine) AudioSystem.getLine(info);
                    line.open(format, data.length);
                    line.start();
                    line.write(data, 0, data.length);
                    line.drain();
                    line.close();
                } catch (Exception ignored) {}
            });
        }

        private byte[] synthesizeJump() {
            int rate = 44100;
            int len = (int) (rate * 0.08); // 80ms
            byte[] buf = new byte[len];
            double phase = 0;
            for (int i = 0; i < len; i++) {
                double frac = (double) i / len;
                double freq = 420 + frac * 480; // upward sweep 420Hz -> 900Hz
                phase += 2.0 * Math.PI * freq / rate;
                double envelope = 1.0 - frac * 0.4;
                buf[i] = (byte) (Math.sin(phase) * 80 * envelope);
            }
            return buf;
        }

        private byte[] synthesizeScore() {
            int rate = 44100;
            int len = (int) (rate * 0.16); // 160ms
            byte[] buf = new byte[len];
            double phase = 0;
            for (int i = 0; i < len; i++) {
                double frac = (double) i / len;
                double freq = (frac < 0.5) ? 659.25 : 880.0; // Two musical notes (E5 -> A5)
                phase += 2.0 * Math.PI * freq / rate;
                double envelope = Math.max(0, 1.0 - (frac % 0.5) * 1.6);
                buf[i] = (byte) (Math.sin(phase) * 75 * envelope);
            }
            return buf;
        }

        private byte[] synthesizeHit() {
            int rate = 44100;
            int len = (int) (rate * 0.18); // 180ms
            byte[] buf = new byte[len];
            double phase = 0;
            for (int i = 0; i < len; i++) {
                double frac = (double) i / len;
                double freq = Math.max(60, 320 - frac * 240); // downward pitch 320Hz -> 80Hz
                phase += 2.0 * Math.PI * freq / rate;
                double noise = (Math.random() - 0.5) * 40 * (1.0 - frac);
                double envelope = 1.0 - frac;
                buf[i] = (byte) ((Math.sin(phase) * 70 + noise) * envelope);
            }
            return buf;
        }
    }


    // =========================================================================
    // --- Application Launcher ---
    // =========================================================================
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("Flappy Bird");
            FlappyBird gamePanel = new FlappyBird();
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setResizable(false);
            frame.add(gamePanel);
            frame.pack();
            frame.setLocationRelativeTo(null); // Center on screen
            frame.setVisible(true);
        });
    }
}
