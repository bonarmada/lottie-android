package com.airbnb.lottie.model.layer;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.support.annotation.Nullable;
import android.support.v4.util.LongSparseArray;

import com.airbnb.lottie.LottieComposition;
import com.airbnb.lottie.LottieDrawable;
import com.airbnb.lottie.LottieProperty;
import com.airbnb.lottie.TextDelegate;
import com.airbnb.lottie.animation.content.ContentGroup;
import com.airbnb.lottie.animation.keyframe.BaseKeyframeAnimation;
import com.airbnb.lottie.animation.keyframe.TextKeyframeAnimation;
import com.airbnb.lottie.animation.keyframe.ValueCallbackKeyframeAnimation;
import com.airbnb.lottie.model.DocumentData;
import com.airbnb.lottie.model.DocumentData.Justification;
import com.airbnb.lottie.model.Font;
import com.airbnb.lottie.model.FontCharacter;
import com.airbnb.lottie.model.animatable.AnimatableTextProperties;
import com.airbnb.lottie.model.content.ShapeGroup;
import com.airbnb.lottie.utils.Utils;
import com.airbnb.lottie.value.LottieValueCallback;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TextLayer extends BaseLayer {
  // Capacity is 2 because emojis are 2 characters. Some are longer in which case, the capacity will
  // be expanded but that should be pretty rare.
  private final StringBuilder stringBuilder = new StringBuilder(2);
  private final RectF rectF = new RectF();
  private final Matrix matrix = new Matrix();
  private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG) {{
    setStyle(Style.FILL);
  }};
  private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG) {{
    setStyle(Style.STROKE);
  }};
  private final Map<FontCharacter, List<ContentGroup>> contentsForCharacter = new HashMap<>();
  private final LongSparseArray<String> codePointCache = new LongSparseArray<String>();
  private final TextKeyframeAnimation textAnimation;
  private final LottieDrawable lottieDrawable;
  private final LottieComposition composition;
  @Nullable
  private BaseKeyframeAnimation<Integer, Integer> colorAnimation;
  @Nullable
  private BaseKeyframeAnimation<Integer, Integer> strokeColorAnimation;
  @Nullable
  private BaseKeyframeAnimation<Float, Float> strokeWidthAnimation;
  @Nullable
  private BaseKeyframeAnimation<Float, Float> trackingAnimation;

  TextLayer(LottieDrawable lottieDrawable, Layer layerModel) {
    super(lottieDrawable, layerModel);
    this.lottieDrawable = lottieDrawable;
    composition = layerModel.getComposition();
    //noinspection ConstantConditions
    textAnimation = layerModel.getText().createAnimation();
    textAnimation.addUpdateListener(this);
    addAnimation(textAnimation);

    AnimatableTextProperties textProperties = layerModel.getTextProperties();
    if (textProperties != null && textProperties.color != null) {
      colorAnimation = textProperties.color.createAnimation();
      colorAnimation.addUpdateListener(this);
      addAnimation(colorAnimation);
    }

    if (textProperties != null && textProperties.stroke != null) {
      strokeColorAnimation = textProperties.stroke.createAnimation();
      strokeColorAnimation.addUpdateListener(this);
      addAnimation(strokeColorAnimation);
    }

    if (textProperties != null && textProperties.strokeWidth != null) {
      strokeWidthAnimation = textProperties.strokeWidth.createAnimation();
      strokeWidthAnimation.addUpdateListener(this);
      addAnimation(strokeWidthAnimation);
    }

    if (textProperties != null && textProperties.tracking != null) {
      trackingAnimation = textProperties.tracking.createAnimation();
      trackingAnimation.addUpdateListener(this);
      addAnimation(trackingAnimation);
    }
  }

  @Override
  public void getBounds(RectF outBounds, Matrix parentMatrix, boolean applyParents) {
    super.getBounds(outBounds, parentMatrix, applyParents);
    // TODO: use the correct text bounds.
    outBounds.set(0, 0, composition.getBounds().width(), composition.getBounds().height());
  }

  @Override
  void drawLayer(Canvas canvas, Matrix parentMatrix, int parentAlpha) {
    canvas.save();
    if (!lottieDrawable.useTextGlyphs()) {
      canvas.setMatrix(parentMatrix);
    }
    DocumentData documentData = textAnimation.getValue();
    Font font = composition.getFonts().get(documentData.fontName);
    if (font == null) {
      // Something is wrong.
      canvas.restore();
      return;
    }

    if (colorAnimation != null) {
      fillPaint.setColor(colorAnimation.getValue());
    } else {
      fillPaint.setColor(documentData.color);
    }

    if (strokeColorAnimation != null) {
      strokePaint.setColor(strokeColorAnimation.getValue());
    } else {
      strokePaint.setColor(documentData.strokeColor);
    }
    int opacity = transform.getOpacity() == null ? 100 : transform.getOpacity().getValue();
    int alpha = opacity * 255 / 100;
    fillPaint.setAlpha(alpha);
    strokePaint.setAlpha(alpha);

    if (strokeWidthAnimation != null) {
      strokePaint.setStrokeWidth(strokeWidthAnimation.getValue());
    } else {
      float parentScale = Utils.getScale(parentMatrix);
      strokePaint.setStrokeWidth((float) (documentData.strokeWidth * Utils.dpScale() * parentScale));
    }

    if (lottieDrawable.useTextGlyphs()) {
      drawTextGlyphs(documentData, parentMatrix, font, canvas);
    } else {
      drawTextWithFont(documentData, font, parentMatrix, canvas);
    }

    canvas.restore();
  }

  private void drawTextGlyphs(
      DocumentData documentData, Matrix parentMatrix, Font font, Canvas canvas) {
    float fontScale = (float) documentData.size / 100f;
    float parentScale = Utils.getScale(parentMatrix);

    String text = documentData.text;

    // Line height
    float lineHeight = (float) documentData.lineHeight * Utils.dpScale();

    // Split full text in multiple lines
    List<String> textLines = getTextLines(text);
    int textLineCount = textLines.size();
    for (int l = 0; l < textLineCount; l++) {

      String textLine = textLines.get(l);
      float textLineWidth = getTextLineWidthForGlyphs(textLine, font, fontScale, parentScale);

      canvas.save();

      // Apply horizontal justification
      applyJustification(documentData.justification, canvas, textLineWidth);

      // Center text vertically
      float multilineTranslateY = (textLineCount - 1) * lineHeight / 2;
      float translateY = l * lineHeight - multilineTranslateY;
      canvas.translate(0, translateY);

      // Draw each line
      drawGlyphTextLine(textLine, documentData, parentMatrix, font, canvas, parentScale, fontScale);

      // Reset canvas
      canvas.restore();
    }
  }

  private void drawGlyphTextLine(String text, DocumentData documentData, Matrix parentMatrix,
      Font font, Canvas canvas, float parentScale, float fontScale) {
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      int characterHash = FontCharacter.hashFor(c, font.getFamily(), font.getStyle());
      FontCharacter character = composition.getCharacters().get(characterHash);
      if (character == null) {
        // Something is wrong. Potentially, they didn't export the text as a glyph.
        continue;
      }
      drawCharacterAsGlyph(character, parentMatrix, fontScale, documentData, canvas);
      float tx = (float) character.getWidth() * fontScale * Utils.dpScale() * parentScale;
      // Add tracking
      float tracking = documentData.tracking / 10f;
      if (trackingAnimation != null) {
        tracking += trackingAnimation.getValue();
      }
      tx += tracking * parentScale;
      canvas.translate(tx, 0);
    }
  }

  private void drawTextWithFont(
      DocumentData documentData, Font font, Matrix parentMatrix, Canvas canvas) {
    float parentScale = Utils.getScale(parentMatrix);
    Typeface typeface = lottieDrawable.getTypeface(font.getFamily(), font.getStyle());
    if (typeface == null) {
      return;
    }
    String text = documentData.text;
    TextDelegate textDelegate = lottieDrawable.getTextDelegate();
    if (textDelegate != null) {
      text = textDelegate.getTextInternal(text);
    }
    fillPaint.setTypeface(typeface);
    fillPaint.setTextSize((float) (documentData.size * Utils.dpScale()));
    strokePaint.setTypeface(fillPaint.getTypeface());
    strokePaint.setTextSize(fillPaint.getTextSize());

    // Line height
    float lineHeight = (float) documentData.lineHeight * Utils.dpScale();

    // Split full text in multiple lines
    List<String> textLines = getTextLines(text);
    int textLineCount = textLines.size();
    for (int l = 0; l < textLineCount; l++) {

      String textLine = textLines.get(l);
      float textLineWidth = strokePaint.measureText(textLine);

      // Apply horizontal justification
      applyJustification(documentData.justification, canvas, textLineWidth);

      // Center text vertically
      float multilineTranslateY = (textLineCount - 1) * lineHeight / 2;
      float translateY = l * lineHeight - multilineTranslateY;
      canvas.translate(0, translateY);

      // Draw each line
      drawFontTextLine(textLine, documentData, canvas, parentScale);

      // Reset canvas
      canvas.setMatrix(parentMatrix);
    }
  }

  private List<String> getTextLines(String text) {
    // Split full text by carriage return character
    String formattedText = text.replaceAll("\r\n", "\r")
        .replaceAll("\n", "\r");
    String[] textLinesArray = formattedText.split("\r");
    return Arrays.asList(textLinesArray);
  }

  private void drawFontTextLine(String text, DocumentData documentData, Canvas canvas, float parentScale) {
    for (int i = 0; i < text.length(); ) {
      String charString = codePointToString(text, i);
      i += charString.length();
      drawCharacterFromFont(charString, documentData, canvas);
      float charWidth = fillPaint.measureText(charString, 0, 1);
      // Add tracking
      float tracking = documentData.tracking / 10f;
      if (trackingAnimation != null) {
        tracking += trackingAnimation.getValue();
      }
      float tx = charWidth + tracking * parentScale;
      canvas.translate(tx, 0);
    }
  }

  private float getTextLineWidthForGlyphs(
      String textLine, Font font, float fontScale, float parentScale) {
    float textLineWidth = 0;
    for (int i = 0; i < textLine.length(); i++) {
      char c = textLine.charAt(i);
      int characterHash = FontCharacter.hashFor(c, font.getFamily(), font.getStyle());
      FontCharacter character = composition.getCharacters().get(characterHash);
      if (character == null) {
        continue;
      }
      textLineWidth += character.getWidth() * fontScale * Utils.dpScale() * parentScale;
    }
    return textLineWidth;
  }

  private void applyJustification(Justification justification, Canvas canvas, float textLineWidth) {
    switch (justification) {
      case LEFT_ALIGN:
        // Do nothing. Default is left aligned.
        break;
      case RIGHT_ALIGN:
        canvas.translate(-textLineWidth, 0);
        break;
      case CENTER:
        canvas.translate(-textLineWidth / 2, 0);
        break;
    }
  }

  private void drawCharacterAsGlyph(
      FontCharacter character,
      Matrix parentMatrix,
      float fontScale,
      DocumentData documentData,
      Canvas canvas) {
    List<ContentGroup> contentGroups = getContentsForCharacter(character);
    for (int j = 0; j < contentGroups.size(); j++) {
      Path path = contentGroups.get(j).getPath();
      path.computeBounds(rectF, false);
      matrix.set(parentMatrix);
      matrix.preTranslate(0, (float) -documentData.baselineShift * Utils.dpScale());
      matrix.preScale(fontScale, fontScale);
      path.transform(matrix);
      if (documentData.strokeOverFill) {
        drawGlyph(path, fillPaint, canvas);
        drawGlyph(path, strokePaint, canvas);
      } else {
        drawGlyph(path, strokePaint, canvas);
        drawGlyph(path, fillPaint, canvas);
      }
    }
  }

  private void drawGlyph(Path path, Paint paint, Canvas canvas) {
    if (paint.getColor() == Color.TRANSPARENT) {
      return;
    }
    if (paint.getStyle() == Paint.Style.STROKE && paint.getStrokeWidth() == 0) {
      return;
    }
    canvas.drawPath(path, paint);
  }

  private void drawCharacterFromFont(String character, DocumentData documentData, Canvas canvas) {
    if (documentData.strokeOverFill) {
      drawCharacter(character, fillPaint, canvas);
      drawCharacter(character, strokePaint, canvas);
    } else {
      drawCharacter(character, strokePaint, canvas);
      drawCharacter(character, fillPaint, canvas);
    }
  }

  private void drawCharacter(String character, Paint paint, Canvas canvas) {
    if (paint.getColor() == Color.TRANSPARENT) {
      return;
    }
    if (paint.getStyle() == Paint.Style.STROKE && paint.getStrokeWidth() == 0) {
      return;
    }
    canvas.drawText(character, 0, character.length(), 0, 0, paint);
  }

  private List<ContentGroup> getContentsForCharacter(FontCharacter character) {
    if (contentsForCharacter.containsKey(character)) {
      return contentsForCharacter.get(character);
    }
    List<ShapeGroup> shapes = character.getShapes();
    int size = shapes.size();
    List<ContentGroup> contents = new ArrayList<>(size);
    for (int i = 0; i < size; i++) {
      ShapeGroup sg = shapes.get(i);
      contents.add(new ContentGroup(lottieDrawable, this, sg));
    }
    contentsForCharacter.put(character, contents);
    return contents;
  }

  private String codePointToString(String text, int startIndex) {
    int firstCodePoint = text.codePointAt(startIndex);
    int firstCodePointLength = Character.charCount(firstCodePoint);
    int key = firstCodePoint;
    int index = startIndex + firstCodePointLength;
    while (index < text.length()) {
      int nextCodePoint = text.codePointAt(index);
      if (!isModifier(nextCodePoint)) {
        break;
      }
      int nextCodePointLength = Character.charCount(nextCodePoint);
      index += nextCodePointLength;
      key = key * 31 + nextCodePoint;
    }

    if (codePointCache.get(key,null) != null) {
      return codePointCache.get(key);
    }

    stringBuilder.setLength(0);
    for (int i = startIndex; i < index; ) {
      int codePoint = text.codePointAt(i);
      stringBuilder.appendCodePoint(codePoint);
      i += Character.charCount(codePoint);
    }
    String str = stringBuilder.toString();
    codePointCache.put(key, str);
    return str;
  }

  private boolean isModifier(int codePoint) {
    return Character.getType(codePoint) == Character.FORMAT ||
        Character.getType(codePoint) == Character.MODIFIER_SYMBOL ||
        Character.getType(codePoint) == Character.NON_SPACING_MARK ||
        Character.getType(codePoint) == Character.OTHER_SYMBOL ||
        Character.getType(codePoint) == Character.SURROGATE;
  }

  @SuppressWarnings("unchecked")
  @Override
  public <T> void addValueCallback(T property, @Nullable LottieValueCallback<T> callback) {
    super.addValueCallback(property, callback);
    if (property == LottieProperty.COLOR) {
      if (colorAnimation != null) {
        colorAnimation.setValueCallback((LottieValueCallback<Integer>) callback);
      } else {
        if (callback == null) {
          if (colorAnimation != null) {
            removeAnimation(colorAnimation);
          }
          colorAnimation = null;
        } else {
          colorAnimation = new ValueCallbackKeyframeAnimation<>((LottieValueCallback<Integer>) callback);
          colorAnimation.addUpdateListener(this);
          addAnimation(colorAnimation);
        }
      }
    } else if (property == LottieProperty.STROKE_COLOR) {
      if (strokeColorAnimation != null) {
        strokeColorAnimation.setValueCallback((LottieValueCallback<Integer>) callback);
      } else {
        if (callback == null) {
          if (strokeColorAnimation != null) {
            removeAnimation(strokeColorAnimation);
          }
          strokeColorAnimation = null;
        } else {
          strokeColorAnimation = new ValueCallbackKeyframeAnimation<>((LottieValueCallback<Integer>) callback);
          strokeColorAnimation.addUpdateListener(this);
          addAnimation(strokeColorAnimation);
        }
      }
    } else if (property == LottieProperty.STROKE_WIDTH) {
      if (strokeWidthAnimation != null) {
        strokeWidthAnimation.setValueCallback((LottieValueCallback<Float>) callback);
      } else {
        if (callback == null) {
          if (strokeWidthAnimation != null) {
            removeAnimation(strokeWidthAnimation);
          }
          strokeWidthAnimation = null;
        } else {
          strokeWidthAnimation = new ValueCallbackKeyframeAnimation<>((LottieValueCallback<Float>) callback);
          strokeWidthAnimation.addUpdateListener(this);
          addAnimation(strokeWidthAnimation);
        }
      }
    } else if (property == LottieProperty.TEXT_TRACKING) {
      if (trackingAnimation != null) {
        trackingAnimation.setValueCallback((LottieValueCallback<Float>) callback);
      } else {
        if (callback == null) {
          if (trackingAnimation != null) {
            removeAnimation(trackingAnimation);
          }
          trackingAnimation = null;
        } else {
          trackingAnimation = new ValueCallbackKeyframeAnimation<>((LottieValueCallback<Float>) callback);
          trackingAnimation.addUpdateListener(this);
          addAnimation(trackingAnimation);
        }
      }
    }
  }
}
