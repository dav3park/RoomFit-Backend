package com.roomfit.consultation;

import com.roomfit.common.CustomException;
import com.roomfit.common.ErrorCode;
import org.springframework.web.multipart.MultipartFile;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.Set;

final class ConsultationImage {
    private ConsultationImage() {}
    static String dataUrl(MultipartFile file) {
        if (file.isEmpty() || file.getSize() > 5 * 1024 * 1024
                || !Set.of("image/jpeg", "image/png").contains(file.getContentType() == null ? "" : file.getContentType())) invalid();
        // Read dimensions before decoding; normalize pixels to discard image metadata.
        try (var input = ImageIO.createImageInputStream(file.getInputStream())) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) { invalid(); return ""; }
            var reader = readers.next();
            try {
                reader.setInput(input);
                String format = reader.getFormatName().toLowerCase(java.util.Locale.ROOT);
                if (!Set.of("png", "jpeg", "jpg").contains(format)
                        || (format.equals("png") != "image/png".equals(file.getContentType()))) invalid();
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || (long) width * height > 25_000_000) invalid();
                double scale = Math.min(1, 1024.0 / Math.max(width, height));
                var parameters = reader.getDefaultReadParam();
                int sample = Math.max(1, (int) Math.ceil(Math.max(width, height) / 1024.0));
                parameters.setSourceSubsampling(sample, sample, 0, 0);
                BufferedImage original = reader.read(0, parameters);
                BufferedImage normalized = new BufferedImage(Math.max(1, (int) (width * scale)),
                        Math.max(1, (int) (height * scale)), BufferedImage.TYPE_INT_RGB);
                var graphics = normalized.createGraphics();
                try { graphics.setColor(java.awt.Color.WHITE); graphics.fillRect(0, 0, normalized.getWidth(), normalized.getHeight());
                    graphics.drawImage(original, 0, 0, normalized.getWidth(), normalized.getHeight(), null); }
                finally { graphics.dispose(); }
                var bytes = new ByteArrayOutputStream();
                if (!ImageIO.write(normalized, "jpeg", bytes)) invalid();
                return "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(bytes.toByteArray());
            } finally { reader.dispose(); }
        } catch (CustomException e) { throw e; }
        catch (Exception e) { invalid(); return ""; }
    }
    private static void invalid() { throw new CustomException(ErrorCode.INVALID_CONSULTATION_IMAGE); }
}
